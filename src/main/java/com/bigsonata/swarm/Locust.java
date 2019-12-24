package com.bigsonata.swarm;

import com.bigsonata.swarm.common.Disposable;
import com.bigsonata.swarm.common.Initializable;
import com.bigsonata.swarm.common.Utils;
import com.bigsonata.swarm.interop.Message;
import com.bigsonata.swarm.interop.Transport;
import com.bigsonata.swarm.interop.ZeroTransport;
import com.bigsonata.swarm.stats.RequestFailure;
import com.bigsonata.swarm.stats.RequestSuccess;
import com.bigsonata.swarm.stats.StatsService;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Locust class exposes all the APIs of locust4j. Use Locust.getInstance() to get a Locust
 * singleton.
 */
public class Locust implements Disposable, Initializable {
  private static final Logger logger = LoggerFactory.getLogger(Locust.class.getCanonicalName());
  private static Locust instance = null;

  /**
   * Every locust4j instance registers a unique nodeID to the master when it makes a connection.
   * NodeID is kept by Runner.
   */
  protected String nodeID = null;

  /** Number of clients required by the master, locust4j use threads to simulate clients. */
  protected int numCrons = 0;

  /** Actual number of clients spawned */
  protected AtomicInteger actualNumClients = new AtomicInteger(0);

  Context context = null;
  private Builder builder;
  private Transport transport = null;
  private boolean started = false;
  private Scheduler scheduler;
  private StatsService statsService = null;
  /** Current state of runner. */
  private AtomicReference<State> state = new AtomicReference<>(State.IDLE);
  /** Task instances submitted by user. */
  private List<Cron> prototypes;

  /** Hatch rate required by the master. Hatch rate means clients/s. */
  private double hatchRate = 0;

  private HeartBeatService heartBeatService;

  private Locust(Builder builder) {
    this.builder = builder;
    this.nodeID = Utils.getNodeID(builder.randomSeed);
    initialize();

    Locust.instance = this;
  }

  public void initialize() {
    initializeScheduler();
    initializeShutdownHook();
    initializeContext();
    initializeTransport();
    initializeStatsService();
//    initializeHeartBeatService();
  }

  private void initializeContext() {
    this.context =
        Context.getInstance()
            .setLocust(this)
            .setMasterHost(builder.masterHost)
            .setMasterPort(builder.masterPort)
            .setStatInterval(builder.statInterval)
            .setScheduler(scheduler);
  }

  private void initializeScheduler() {
    scheduler =
        Scheduler.newBuilder()
            .setBufferSize(builder.bufferSize)
            .setMaxRps(builder.maxRps)
            .setParallelism(builder.threads)
            .setLocust(this)
            .build();
  }

  private void onReady(){
    sendReady();
    initializeHeartBeatService();
  }

  private void initializeTransport() {
    transport =
        new ZeroTransport(context) {
          @Override
          public void onMessage(Message message) throws Exception {
            Locust.this.onMessage(message);
          }

          @Override
          public void onConnected() {
            super.onConnected();
            onReady();
          }
        };

    try {
      transport.initialize();
    } catch (Exception e) {
      e.printStackTrace();
      logger.error("Failed to initialize Locust transport. Terminating now...");
      System.exit(-1);
    }
  }

  private void initializeStatsService() {
    statsService =
        new StatsService(context) {
          @Override
          public void onData(Map data) {
            Locust.this.sendReport(data);
          }
        };
    statsService.initialize();
  }

  private void initializeHeartBeatService() {
    heartBeatService = new HeartBeatService(transport, this);
    heartBeatService.initialize();
  }

  private void sendReport(Map data) {
    State currentState = this.state.get();

    boolean updatable = (currentState == State.Running || currentState == State.Hatching);
    logger.info(">> Sending report state={}\tupdatable={}", currentState.toString(), updatable);
    if (!updatable) {
      // no need to send report
      return;
    }

    data.put("user_count", actualNumClients.get());

    try {
      transport.send(new Message("stats", data, nodeID));
    } catch (Exception ex) {
      ex.printStackTrace();
      logger.error(ex.getMessage());
    }
  }

  private void onMessage(Message message) throws Exception {
    if (message.isHatch()) {
      onHatch(message);
      return;
    }

    if (message.isStop()) {
      onStop();
      return;
    }

    if (message.isQuit()) {
      onQuit();
    }
  }

  private void onQuit() {
    logger.info("Got `quit` message from master, shutting down...");
    System.exit(0);
  }

  private void onStop() throws Exception {
    if (!isStoppable()) {
      return;
    }
    logger.info("Received message STOP from master, all the workers are stopped");
    this.state.set(State.Stopped);
    this.scheduler.stop();

    transport.send(new Message("client_stopped", null, nodeID));
    transport.send(new Message("client_ready", null, nodeID));
  }

  private void onHatch(Message message) throws Exception {
    sendHatching();
    Map data = message.getData();
    Float hatchRate = Float.valueOf(data.get("hatch_rate").toString());
    int numClients = Integer.valueOf(data.get("num_clients").toString());
    startHatching(numClients, hatchRate.doubleValue());
  }

  /**
   * Add prototypes to Runner, connect to master and wait for messages of master.
   *
   * @param crons List of crons to register
   */
  public void register(Cron... crons) {
    List<Cron> crs = new ArrayList<>();
    for (Cron task : crons) {
      crs.add(task);
    }
    register(crs);
  }

  /**
   * Add prototypes to Runner, connect to master and wait for messages of master.
   *
   * @param crons List of crons to register
   */
  public synchronized void register(List<Cron> crons) {
    if (this.started) {
      // Don't call Locust.register() multiply times.
      return;
    }

    this.prototypes = crons;
    this.state.set(State.Ready);
    this.started = true;
  };

  /**
   * when JVM is shutting down, send a `quit` message to master, then master will remove this slave
   * from its list.
   */
  private void initializeShutdownHook() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.warn("JVM is shutting down...");
                  // tell master that I'm quitting
                  dispose();
                },
                "swarm-shutdown-hook"));
  }

  /**
   * Add a successful record, locust4j will collect it, calculate things like RPS, and sendReport to
   * master.
   *
   * @param type Type (GET, POST or whatever)
   * @param name Name (API name)
   * @param responseTime Response time
   * @param responseLength Content size
   */
  public void recordSuccess(String type, String name, long responseTime, long responseLength) {
    RequestSuccess success = new RequestSuccess();
    success.type = type;
    success.name = name;
    success.responseTime = responseTime;
    success.responseLength = responseLength;
    statsService.report(success);
  }

  public void recordSuccess(String type, String name, long responseTime) {
    this.recordSuccess(type, name, responseTime, 0);
  }

  /**
   * Add a failed record, locust4j will collect it, and sendReport to master.
   *
   * @param type Type (GET, POST or whatever)
   * @param name Name (API name)
   * @param responseTime Response time
   * @param error Error
   */
  public void recordFailure(String type, String name, long responseTime, String error) {
    RequestFailure failure = new RequestFailure();
    failure.type = type;
    failure.name = name;
    failure.responseTime = responseTime;
    failure.error = error;
    statsService.report(failure);
  }

  public State getState() {
    return this.state.get();
  }

  public boolean isStopped() {
    return getState().equals(Locust.State.Stopped);
  }

  private void spawn(int cronCount) {
    logger.info(
        "Hatching and swarming {} clients at the rate of {} clients/s...",
        cronCount,
        this.hatchRate);
    final RateLimiter rateLimiter = RateLimiter.create(this.hatchRate);

    float weightSum = 0;
    for (Cron cron : this.prototypes) {
      weightSum += cron.getWeight();
    }

    // rescale
    for (Cron prototype : this.prototypes) {
      float percent;

      if (0 == weightSum) {
        percent = 1 / (float) this.prototypes.size();
      } else {
        percent = prototype.getWeight() / weightSum;
      }

      int amount = Math.round(cronCount * percent);
      if (weightSum == 0) {
        amount = cronCount / this.prototypes.size();
      }

      logger.info("> {}={}", prototype.getName(), amount);

      for (int i = 1; i <= amount; i++) {
        rateLimiter.acquire();
        if (isStopped()) {
          return;
        }

        Cron clone = prototype.clone();
        clone.initialize(); // initialize them first
        this.scheduler.submit(clone);
        actualNumClients.incrementAndGet();
      }
    }

    this.onHatchCompleted();
  }

  protected void startHatching(int spawnCount, double hatchRate) {
    State currentState = this.state.get();
    if (currentState != State.Ready && currentState != State.Stopped) {
      logger.error("Invalid state. Terminating now...");
      this.dispose();
      System.exit(-1);
    }
    statsService.clearAll();
    this.numCrons = spawnCount;

    logger.info("Start hatching...");
    logger.info("> spawnCount={}", spawnCount);
    logger.info("> hatchRate={}", hatchRate);

    this.state.set(State.Hatching);

    this.actualNumClients.set(0);
    this.hatchRate = hatchRate;
    this.numCrons = spawnCount;
    this.spawn(numCrons);
  }

  protected void onHatchCompleted() {
    sendHatchCompleted();
    this.state.set(State.Running);
  }

  /**
   * Send data to Locust Master
   *
   * @param type Type of message
   * @param data Accompanied data
   */
  private void send(String type, Map data) {
    logger.info("Sending {} message...", type);
    try {
      transport.send(new Message(type, data, this.nodeID));
    } catch (Exception e) {
      e.printStackTrace();
      logger.error("Can NOT send `{}` message", type);
    }
  }

  private void send(String type) {
    send(type, null);
  }

  private void sendHatchCompleted() {
    logger.info("Hatch completed!");
    Map data = new HashMap(1);
    data.put("count", this.numCrons);
    send("hatch_complete", data);
  }

  private void sendHatching() {
    send("hatching");
  }

  protected void sendQuit() {
    logger.info("Quitting...");
    send("quit");
  }

  protected void sendReady() {
    logger.info("Ready!");
    send("client_ready");
  }

  public void dispose() {
    if (this.state.get() == State.Stopped) {
      return;
    }
    logger.warn("Disposing...");
    sendQuit();

    this.state.set(State.Stopped);

    for (Cron cron : prototypes) {
      cron.dispose();
    }

    scheduler.dispose();
    transport.dispose();

    logger.info("Bye bye!");
  }

  private boolean isStoppable() {
    State currentState = this.state.get();
    return currentState == State.Running || currentState == State.Hatching;
  }

  /** State of runner */
  enum State {
    IDLE,
    /** Runner is ready to receive message from master. */
    Ready,

    /** Runner is submitting prototypes to its thread pool. */
    Hatching,

    /** Runner is done with submitting prototypes. */
    Running,

    /** Runner is stopped, its thread pool is destroyed, the test is stopped. */
    Stopped,
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String masterHost = "127.0.0.1";
    private int masterPort = 5557;
    private int bufferSize = 32768;
    private int threads = 8;
    private int statInterval = 2000;
    private int randomSeed = 0;
    private int maxRps = 0;

    public static Builder newInstance() {
      return new Builder();
    }

    public Locust build() throws Exception {
      if ((bufferSize & (bufferSize - 1)) != 0) {
        throw new Exception("Disruptor capacity must be a power of 2");
      }
      return new Locust(this);
    }

    /**
     * Set master host.
     *
     * @param masterHost Locust host
     * @return The current Builder instance
     */
    public Builder setMasterHost(String masterHost) {
      this.masterHost = masterHost;
      return this;
    }

    /**
     * Set master port.
     *
     * @param masterPort Locust port
     * @return The current Builder instance
     */
    public Builder setMasterPort(int masterPort) {
      this.masterPort = masterPort;
      return this;
    }

    /**
     * Number of threads to stimulate crons
     *
     * @param threads Number of threads
     * @return The current Builder instance
     */
    public Builder setThreads(int threads) {
      this.threads = threads;
      return this;
    }

    /**
     * Set the interval to send statistics data to Locust Master
     *
     * @param statInterval Interval (in ms)
     * @return The current Builder instance
     */
    public Builder setStatInterval(int statInterval) {
      this.statInterval = statInterval;
      return this;
    }

    public Builder setRandomSeed(int randomSeed) {
      this.randomSeed = randomSeed;
      return this;
    }

    /**
     * [Optional] Set the maximum requests per second restricted on this Swarm application
     *
     * @param maxRps A positive number
     * @return The current Builder instance
     */
    public Builder setMaxRps(int maxRps) {
      this.maxRps = maxRps;
      return this;
    }

    /**
     * [Optional] Set the internal buffer size. Default value is 32k which is may enough
     *
     * <p>NOTE: This is the disruptor's ring size. + The size must be a power of 2 + If you set it
     * too small, you may experience low throughput when your Crons run
     *
     * @param bufferSize A positive number which is a power of 2
     * @return The current Builder instance
     */
    public Builder setBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      return this;
    }
  }
}
