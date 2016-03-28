/* Copyright (c) 2015 University of Massachusetts
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * Initial developer(s): V. Arun */
package edu.umass.cs.gigapaxos.testing;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.gigapaxos.PaxosConfig;
import edu.umass.cs.gigapaxos.PaxosConfig.PC;
import edu.umass.cs.gigapaxos.paxospackets.PaxosPacket;
import edu.umass.cs.gigapaxos.paxospackets.RequestPacket;
import edu.umass.cs.gigapaxos.paxosutil.RateLimiter;
import edu.umass.cs.gigapaxos.testing.TESTPaxosConfig.TC;
import edu.umass.cs.nio.AbstractJSONPacketDemultiplexer;
import edu.umass.cs.nio.JSONNIOTransport;
import edu.umass.cs.nio.MessageNIOTransport;
import edu.umass.cs.nio.NIOTransport;
import edu.umass.cs.nio.SSLDataProcessingWorker;
import edu.umass.cs.nio.interfaces.NodeConfig;
import edu.umass.cs.scratch.Executor;
import edu.umass.cs.utils.Config;
import edu.umass.cs.utils.DelayProfiler;
import edu.umass.cs.utils.Util;

/**
 * @author V. Arun
 */
public class TESTPaxosClient {
	static {
		TESTPaxosConfig.load();
	}

	// private static final long createTime = System.currentTimeMillis();
	private static final int RANDOM_REPLAY = (int) (Math.random() * Config
			.getGlobalInt(TC.NUM_GROUPS));

	private static int SEND_POOL_SIZE = Config.getGlobalInt(TC.NUM_CLIENTS);
	// because the single-threaded sender is a bottleneck on multicore
	private static ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) Executors
			.newScheduledThreadPool(SEND_POOL_SIZE);

	private static int totalNoopCount = 0;

	private static int numRequests = 0; // used only for latency
	private static long totalLatency = 0;
	private static double movingAvgLatency = 0;
	private static double movingAvgDeviation = 0;
	private static int numRtxReqs = 0;
	private static int rtxCount = 0;
	private static long lastResponseReceivedTime = System.currentTimeMillis();

	private static synchronized void incrTotalLatency(long ms) {
		totalLatency += ms;
		numRequests++;
	}

	private static synchronized void updateMovingAvgLatency(long ms) {
		movingAvgLatency = Util.movingAverage(ms, movingAvgLatency);
		movingAvgDeviation = Util.movingAverage(ms, movingAvgDeviation);
	}

	private static synchronized void updateLatency(long ms) {
		lastResponseReceivedTime = System.currentTimeMillis();
		incrTotalLatency(ms);
		updateMovingAvgLatency(ms);
	}

	private static synchronized double getTimeout() {
		return Math.max(movingAvgLatency + 4 * movingAvgDeviation,
				TESTPaxosConfig.CLIENT_REQ_RTX_TIMEOUT);
	}

	private static synchronized double getAvgLatency() {
		return totalLatency * 1.0 / numRequests;
	}

	private static synchronized void incrRtxCount() {
		rtxCount++;
	}

	private static synchronized void incrNumRtxReqs() {
		numRtxReqs++;
	}

	private static synchronized int getRtxCount() {
		return rtxCount;
	}

	private static synchronized int getNumRtxReqs() {
		return numRtxReqs;
	}

	protected synchronized static void resetLatencyComputation(
			TESTPaxosClient[] clients) {
		totalLatency = 0;
		numRequests = 0;
		for (TESTPaxosClient client : clients)
			client.runReplyCount = 0;
	}

	private MessageNIOTransport<Integer, JSONObject> niot;
	private final NodeConfig<Integer> nc;
	private final int myID;
	private int totReqCount = 0;
	private int totReplyCount = 0;
	private int runReplyCount = 0;
	private int noopCount = 0;

	private final ConcurrentHashMap<Long, RequestAndCreateTime> requests = new ConcurrentHashMap<Long, RequestAndCreateTime>();
	// private final ConcurrentHashMap<Long, Long> requestCreateTimes = new
	// ConcurrentHashMap<Long, Long>();
	private final Timer timer; // for retransmission

	private static Logger log = Logger.getLogger(TESTPaxosClient.class
			.getName());

	// PaxosManager.getLogger();

	private synchronized int incrReplyCount() {
		this.runReplyCount++;
		return this.totReplyCount++;
	}

	private synchronized int incrReqCount() {
		return ++this.totReqCount;
	}

	private synchronized int incrNoopCount() {
		incrTotalNoopCount();
		return this.noopCount++;
	}

	private synchronized static int incrTotalNoopCount() {
		return totalNoopCount++;
	}

	protected synchronized static int getTotalNoopCount() {
		return totalNoopCount;
	}

	private synchronized int getTotalReplyCount() {
		return this.totReplyCount;
	}

	private synchronized int getRunReplyCount() {
		return this.runReplyCount;
	}

	private synchronized int getTotalRequestCount() {
		return this.totReqCount;
	}

	private synchronized int getNoopCount() {
		return this.noopCount;
	}

	synchronized void close() {
		executor.shutdownNow();
		this.timer.cancel();
		this.niot.stop();
	}

	synchronized boolean noOutstanding() {
		return this.requests.isEmpty();
	}

	/******** Start of ClientPacketDemultiplexer ******************/
	private class ClientPacketDemultiplexer extends
			AbstractJSONPacketDemultiplexer {
		private final TESTPaxosClient client;

		private ClientPacketDemultiplexer(TESTPaxosClient tpc) {
			super(1);
			this.client = tpc;
			this.register(PaxosPacket.PaxosPacketType.PAXOS_PACKET);
			this.setThreadName("" + tpc.myID);
		}

		public boolean handleMessage(JSONObject msg) {
			// long t = System.nanoTime();
			try {
				RequestPacket request = new RequestPacket(msg);
				RequestAndCreateTime sentRequest = requests.remove(request
						.getRequestID());
				if (sentRequest != null) {
					long latency = System.currentTimeMillis()
							- sentRequest.createTime;
					client.incrReplyCount();
					Level level = Level.FINE;
					if (log.isLoggable(level))
						TESTPaxosClient.log
								.log(level,
										"Client {0} received response #{1} with latency {2} [{3}] : {4} {5}",
										new Object[] {
												client.myID,
												client.getTotalReplyCount(),
												latency,
												request.getDebugInfo(),
												request.getSummary(log
														.isLoggable(level)),
												request });

					DelayProfiler.updateInterArrivalTime("response_rate", 1,
							100);
					// DelayProfiler.updateRate("response_rate2", 1000, 10);

					updateLatency(latency);
					synchronized (client) {
						client.notify();
					}
				} else {
					Level level = Level.FINE;
					TESTPaxosClient.log
							.log(level,
									"Client {0} received PHANTOM response #{1} [{2}] for request {3} : {4}",
									new Object[] {
											client.myID,
											client.getTotalReplyCount(),
											request.getDebugInfo(),
											request.requestID,
											request.getSummary(log
													.isLoggable(level)) });
				}
				if (request.isNoop())
					client.incrNoopCount();
				// requests.remove(request.requestID);
			} catch (JSONException je) {
				log.severe(this + " incurred JSONException while processing "
						+ msg);
				je.printStackTrace();
			}
			// if (Util.oneIn(100))
			// DelayProfiler.updateDelayNano("handleMessage", t);
			return true;
		}
	}

	/******** End of ClientPacketDemultiplexer ******************/

	private class Retransmitter extends TimerTask {
		final int id;
		final RequestPacket req;
		final double timeout;
		boolean first;

		Retransmitter(int id, RequestPacket req) {
			this(id, req, getTimeout());
			first = true;
		}

		Retransmitter(int id, RequestPacket req, double timeout) {
			this.id = id;
			this.req = req;
			this.timeout = timeout;
			first = false;
		}

		public void run() {
			try {
				// checks parent queue
				if (requests.containsKey(req.requestID)) {
					incrRtxCount();
					if (first)
						incrNumRtxReqs();
					log.log(Level.INFO, "{0}{1}{2}{3}{4}{5}", new Object[] {
							"Retransmitting request ", "" + req.requestID,
							" to node ", id, ": ", req });
					sendToID(id, req.toJSONObject());
					timer.schedule(new Retransmitter(id, req, timeout * 2),
							(long) (timeout * 2));
				}
			} catch (IOException | JSONException e) {
				e.printStackTrace();
			}
		}
	}

	protected TESTPaxosClient(int id, NodeConfig<Integer> nc)
			throws IOException {
		this.myID = id;
		this.nc = (nc == null ? TESTPaxosConfig.getNodeConfig() : nc);
		niot = (new MessageNIOTransport<Integer, JSONObject>(id, this.nc,
				(new ClientPacketDemultiplexer(this)), true,
				SSLDataProcessingWorker.SSL_MODES.valueOf(Config
						.getGlobalString(PC.CLIENT_SSL_MODE))));
		this.timer = new Timer(TESTPaxosClient.class.getSimpleName() + myID);
	}

	private static final boolean PIN_CLIENT = Config
			.getGlobalBoolean(TC.PIN_CLIENT);

	private boolean sendRequest(RequestPacket req) throws IOException,
			JSONException {
		int[] group = TESTPaxosConfig.getGroup(req.getPaxosID());
		int index = !PIN_CLIENT ? (int) (req.requestID % group.length)
				: (int) (myID % group.length);
		assert (!(index < 0 || index >= group.length || TESTPaxosConfig
				.isCrashed(group[index])));
		return this.sendRequest(group[index], req);
	}

	private static final boolean ENABLE_REQUEST_COUNTS = true;
	static ConcurrentHashMap<Integer, Integer> reqCounts = new ConcurrentHashMap<Integer, Integer>();

	synchronized static void urc(int id) {
		if (ENABLE_REQUEST_COUNTS) {
			reqCounts.putIfAbsent(id, 0);
			reqCounts.put(id, reqCounts.get(id) + 1);
		}
	}

	class RequestAndCreateTime extends RequestPacket {
		final long createTime = System.currentTimeMillis();

		RequestAndCreateTime(RequestPacket request) {
			super(request);
		}
	}

	private static final int CLIENT_PORT_OFFSET = PaxosConfig
			.getClientPortOffset();

	protected boolean sendRequest(int id, RequestPacket req)
			throws IOException, JSONException {
		InetAddress address = nc.getNodeAddress(id);
		assert (address != null) : id;
		Level level = Level.FINE;
		if (log.isLoggable(level))
			log.log(level,
					"Sending request to node {0}:{1}:{2} {2}",
					new Object[] { id, address, nc.getNodePort(id),
							req.getSummary(log.isLoggable(level)) });
		if (this.requests.put(req.requestID, new RequestAndCreateTime(req)) != null)
			return false; // collision in integer space
		this.incrReqCount();

		// no retransmission send
		while (this.niot.sendToID(id, req.toJSONObject()) <= 0) {
			try {
				Thread.sleep(req.lengthEstimate() / RequestPacket.SIZE_ESTIMATE
						+ 1);
				log.log(Level.WARNING,
						"{0} retrying send to node {1} probably because of congestion",
						new Object[] { this, id });
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		urc(id);
		// retransmit if enabled
		if (TESTPaxosConfig.ENABLE_CLIENT_REQ_RTX)
			this.timer
					.schedule(new Retransmitter(id, req), (long) getTimeout());
		return true;
	}

	private int sendToID(int id, JSONObject json) throws IOException {
		return this.niot.sendToAddress(
				CLIENT_PORT_OFFSET > 0 ? new InetSocketAddress(this.nc
						.getNodeAddress(id), this.nc.getNodePort(id)
						+ CLIENT_PORT_OFFSET) : new InetSocketAddress(this.nc
						.getNodeAddress(id), this.nc.getNodePort(id)
						+ CLIENT_PORT_OFFSET), json);
	}

	private static final String GIBBERISH = "89432hoicnbsd89233u2eoiwdj-329hbousfnc";
	static String gibberish = Config.getGlobalBoolean(TC.COMPRESSIBLE_REQUEST) ? createGibberishCompressible()
			: createGibberish();

	private static final String createGibberishCompressible() {
		gibberish = GIBBERISH;
		int baggageSize = Config.getGlobalInt(TC.REQUEST_BAGGAGE_SIZE);
		if (gibberish.length() > baggageSize)
			gibberish = gibberish.substring(0, baggageSize);
		else
			while (gibberish.length() < baggageSize)
				gibberish += (baggageSize > 2 * gibberish.length() ? gibberish
						: gibberish.substring(0,
								baggageSize - gibberish.length()));
		Util.assertAssertionsEnabled();
		assert (gibberish.length() == baggageSize);
		return gibberish;
	}

	private static final String createGibberish() {
		int baggageSize = Config.getGlobalInt(TC.REQUEST_BAGGAGE_SIZE);
		byte[] buf = new byte[baggageSize];
		byte[] chars = Util.getAlphanumericAsBytes();
		for (int i = 0; i < baggageSize; i++)
			buf[i] = (chars[(int) (Math.random() * chars.length)]);
		gibberish = new String(buf);
		if (gibberish.length() > baggageSize)
			gibberish = gibberish.substring(0, baggageSize);
		else
			gibberish += gibberish.substring(0,
					baggageSize - gibberish.length());
		Util.assertAssertionsEnabled();
		assert (gibberish.length() == baggageSize);
		return gibberish;
	}

	/**
	 * @return Literally gibberish.
	 */
	public static String getGibberish() {
		return gibberish;
	}

	private RequestPacket makeRequest() {
		long reqID = ((long) (Math.random() * Long.MAX_VALUE));
		RequestPacket req = new RequestPacket(reqID,
		// createGibberish(), // randomly create each string
				gibberish, false);
		return req;
	}

	private static final String TEST_GUID = Config
			.getGlobalString(TC.TEST_GUID);

	private RequestPacket makeRequest(String paxosID) {
		RequestPacket req = this.makeRequest();
		req.putPaxosID(paxosID != null ? paxosID : TEST_GUID, 0);
		return req;
	}

	protected boolean makeAndSendRequest(String paxosID) throws JSONException,
			IOException, InterruptedException, ExecutionException {
		// long t = System.nanoTime();
		RequestPacket req = TESTPaxosClient.this.makeRequest(paxosID);
		return TESTPaxosClient.this.sendRequest(req);
		// if (Util.oneIn(100))
		// DelayProfiler.updateDelayNano("makeAndSendRequest", t);
	}

	protected boolean makeAndSendRequestCallable(String paxosID)
			throws JSONException, IOException, InterruptedException,
			ExecutionException {
		executor.submit(new Callable<Boolean>() {
			public Boolean call() {
				// long t = System.nanoTime();
				RequestPacket req = TESTPaxosClient.this.makeRequest(paxosID);
				try {
					return TESTPaxosClient.this.sendRequest(req);
				} catch (IOException | JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				return null;
				// if (Util.oneIn(100))
				// DelayProfiler.updateDelayNano("makeAndSendRequest", t);
			}
		});
		// while loop to retry inside sendRequest
		return true;
	}

	protected static TESTPaxosClient[] setupClients(NodeConfig<Integer> nc) {
		System.out.println("\n\nInitiating paxos clients setup");
		TESTPaxosClient[] clients = new TESTPaxosClient[Config
				.getGlobalInt(TC.NUM_CLIENTS)];
		for (int i = 0; i < Config.getGlobalInt(TC.NUM_CLIENTS); i++) {
			try {
				clients[i] = new TESTPaxosClient(
						Config.getGlobalInt(TC.TEST_CLIENT_ID) + i, nc);
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
		System.out.println("Completed initiating "
				+ Config.getGlobalInt(TC.NUM_CLIENTS) + " clients");
		return clients;
	}

	private static final double TOTAL_LOAD = Config
			.getGlobalDouble(TC.TOTAL_LOAD);
	private static final int NUM_GROUPS = Config.getGlobalInt(TC.NUM_GROUPS);
	private static final String TEST_GUID_PREFIX = Config
			.getGlobalString(TC.TEST_GUID_PREFIX);

	// private static final double LOAD_THRESHOLD = 10000;

	protected static void sendTestRequests(int numReqs,
			TESTPaxosClient[] clients, double load) throws JSONException,
			IOException, InterruptedException, ExecutionException {
		System.out.print("\nTesting " + "[#requests=" + numReqs
				+ ", request_size=" + gibberish.length() + "B, #clients="
				+ clients.length + ", #groups=" + NUM_GROUPS + ", load="
				+ Util.df(load) + "/s" + "]...");
		long initTime = System.currentTimeMillis();

		Future<?>[] futures = new Future<?>[SEND_POOL_SIZE];
		assert (executor.getCorePoolSize() == SEND_POOL_SIZE);
		// if (TOTAL_LOAD > LOAD_THRESHOLD)
		{
			if (SEND_POOL_SIZE > 0) {
				for (int i = 0; i < SEND_POOL_SIZE; i++) {
					final int j = i;
					futures[j] = executor.submit(new Runnable() {
						public void run() {
							try {
								sendTestRequests(
										// to account for integer division
										j < SEND_POOL_SIZE - 1 ? numReqs
												/ SEND_POOL_SIZE : numReqs
												- numReqs / SEND_POOL_SIZE
												* (SEND_POOL_SIZE - 1),
										clients, false, load / SEND_POOL_SIZE);
							} catch (JSONException | IOException
									| InterruptedException | ExecutionException e) {
								e.printStackTrace();
							}
						}
					});
				}
				for (Future<?> future : futures)
					future.get();
			} else
				sendTestRequests(numReqs, clients, false, load);
		}
		// all done sending if here
		mostRecentSentRate = numReqs * 1000.0
				/ (System.currentTimeMillis() - initTime);
		System.out
				.println("done "
						+ ("sending "
								+ numReqs
								+ " requests in "
								+ Util.df((System.currentTimeMillis() - initTime) / 1000.0)
								+ " secs; average sending rate = "
								+ Util.df(mostRecentSentRate) + "/s"
						// + " \n "+ reqCounts
						));

	}

	private static final int NUM_CLIENTS = Config.getGlobalInt(TC.NUM_CLIENTS);
	private static double mostRecentSentRate = 0;

	private static void sendTestRequests(int numReqs,
			TESTPaxosClient[] clients, boolean warmup, double rate)
			throws JSONException, IOException, InterruptedException,
			ExecutionException {
		if (warmup)
			System.out.print((warmup ? "\nWarming up " : "\nTesting ")
					+ "[#requests=" + numReqs + ", request_size="
					+ gibberish.length() + "B, #clients=" + clients.length
					+ ", #groups=" + NUM_GROUPS + ", load=" + TOTAL_LOAD + "/s"
					+ "]...");
		RateLimiter rateLimiter = new RateLimiter(rate);
		// long initTime = System.currentTimeMillis();
		for (int i = 0; i < numReqs; i++) {
			while (!clients[i % NUM_CLIENTS]
					.makeAndSendRequest(TEST_GUID_PREFIX
							+ ((RANDOM_REPLAY + i) % (NUM_GROUPS))))
				;
			rateLimiter.record();
		}

		if (warmup)
			System.out.println("done");

	}

	protected static void waitForResponses(TESTPaxosClient[] clients,
			long startTime) {
		waitForResponses(clients, startTime, false);
	}

	protected static void waitForResponses(TESTPaxosClient[] clients,
			long startTime, boolean warmup) {
		for (int i = 0; i < Config.getGlobalInt(TC.NUM_CLIENTS); i++) {
			while (clients[i].requests.size() > 0) {
				synchronized (clients[i]) {
					if (clients[i].requests.size() > 0)
						try {
							clients[i].wait(4000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
				}
				System.out
						.println("["
								+ clients[i].myID
								+ "] "
								+ getWaiting(clients)
								+ (getRtxCount() > 0 ? "; #num_total_retransmissions = "
										+ getRtxCount()
										: "")
								+ (getRtxCount() > 0 ? "; num_retransmitted_requests = "
										+ getNumRtxReqs()
										: "")
								+ (!warmup ? "; aggregate response rate = "
										+ Util.df(getTotalThroughput(clients,
												startTime)) + " reqs/sec" : ""));
				if (clients[i].requests.size() > 0)
					try {
						Thread.sleep(1000);
						// System.out.println(DelayProfiler.getStats());
					} catch (Exception e) {
						e.printStackTrace();
					}
			}
		}
	}

	protected static boolean noOutstanding(TESTPaxosClient[] clients) {
		boolean noOutstanding = true;
		for (TESTPaxosClient client : clients)
			noOutstanding = noOutstanding && client.noOutstanding();
		return noOutstanding;
	}

	protected static Set<RequestPacket> getMissingRequests(
			TESTPaxosClient[] clients) {
		Set<RequestPacket> missing = new HashSet<RequestPacket>();
		for (int i = 0; i < clients.length; i++) {
			missing.addAll(clients[i].requests.values());
		}
		return missing;
	}

	private static String getWaiting(TESTPaxosClient[] clients) {
		int total = 0;
		String s = " unfinished requests: [ ";
		for (int i = 0; i < clients.length; i++) {
			s += "C" + i + ":" + clients[i].requests.size() + " ";
			total += clients[i].requests.size();
		}
		s += "]";
		return total + s;
	}

	private static double getTotalThroughput(TESTPaxosClient[] clients,
			long startTime) {
		int totalExecd = 0;
		for (int i = 0; i < clients.length; i++) {
			totalExecd += clients[i].getRunReplyCount();
		}

		return totalExecd * 1000.0 / (System.currentTimeMillis() - startTime);
	}

	protected static void printOutput(TESTPaxosClient[] clients) {
		for (int i = 0; i < Config.getGlobalInt(TC.NUM_CLIENTS); i++) {
			if (clients[i].requests.isEmpty()) {
				System.out.println("\n\n[SUCCESS] requests issued = "
						+ clients[i].getTotalRequestCount()
						+ "; requests turned to no-ops = "
						+ clients[i].getNoopCount() + "; responses received = "
						+ clients[i].getTotalReplyCount() + "\n");
			} else
				System.out.println("\n[FAILURE]: Requests issued = "
						+ clients[i].getTotalRequestCount()
						+ "; requests turned to no-ops = "
						+ clients[i].getNoopCount() + "; responses received = "
						+ clients[i].getTotalReplyCount() + "\n");
		}
	}

	protected static String getAggregateOutput(long delay) {
		return "\n  average_sent_rate = "
				+ Util.df(mostRecentSentRate)
				+ "/s"
				+ "\n  average_response_time = "
				+ Util.df(TESTPaxosClient.getAvgLatency())
				+ "ms"
				+ "\n  average_response_rate = "
				+ Util.df(NUM_REQUESTS
						* 1000.0
						/ (delay - (System.currentTimeMillis() - lastResponseReceivedTime)))
				+ "/s"

				+ "\n  noop_count = " + TESTPaxosClient.getTotalNoopCount();
	}

	private static final int NUM_REQUESTS = Config
			.getGlobalInt(TC.NUM_REQUESTS);

	private static final long LATENCY_THRESHOLD = Config
			.getGlobalLong(TC.PROBE_LATENCY_THRESHOLD);

	private static final int PROBE_MAX_RUNS = Config
			.getGlobalInt(TC.PROBE_MAX_RUNS);

	/**
	 * This method probes for the capacity by multiplicatively increasing the
	 * load until the response rate is at least a threshold fraction of the
	 * injected load and the average response time is within a threshold. These
	 * thresholds are picked up from {@link TESTPaxosConfig}.
	 * 
	 * @param load
	 * @param clients
	 * @return
	 * @throws JSONException
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	private static double probeCapacity(double load, TESTPaxosClient[] clients)
			throws JSONException, IOException, InterruptedException,
			ExecutionException {
		long runDuration = Config.getGlobalLong(TC.PROBE_RUN_DURATION); // seconds
		double responseRate = 0, capacity = 0, latency = Double.MAX_VALUE;
		double threshold = Config.getGlobalDouble(TC.PROBE_LOAD_THRESHOLD), loadIncreaseFactor = Config
				.getGlobalDouble(TC.PROBE_LOAD_INCREASE_FACTOR), minLoadIncreaseFactor = 1.01;
		int runs = 0, consecutiveFailures = 0;

		/**************** Start of capacity probes *******************/
		do {
			if (runs++ > 0)
				// increase probe load only if successful
				if (consecutiveFailures == 0)
					load *= loadIncreaseFactor;
				else
					// scale back if failed
					load *= (1 - (loadIncreaseFactor - 1) / 2);

			/* Two failures => increase more cautiously. Sometimes a failure
			 * happens in the very first run if the JVM is too cold, so we wait
			 * for at least two consecutive failures. */
			if (consecutiveFailures == 2)
				loadIncreaseFactor = (1 + (loadIncreaseFactor - 1) / 2);

			// we are within roughly 0.1% of capacity
			if (loadIncreaseFactor < minLoadIncreaseFactor)
				break;

			int numRunRequests = (int) (load * runDuration);
			long t1 = System.currentTimeMillis();
			sendTestRequests(numRunRequests, clients, load);
			responseRate = numRunRequests * 1000.0
					/ (lastResponseReceivedTime - t1);
			latency = TESTPaxosClient.getAvgLatency();
			if (latency < LATENCY_THRESHOLD)
				capacity = Math.max(capacity, responseRate);
			TESTPaxosClient.resetLatencyComputation(clients);
			boolean success = (responseRate > threshold * load && latency <= LATENCY_THRESHOLD);
			System.out.println("capacity >= " + Util.df(capacity)
					+ "/s; (response_rate=" + Util.df(responseRate)
					+ "/s, average_response_time=" + Util.df(latency) + "ms)"
					+ (!success ? "    !!!!!!!!FAILED!!!!!!!!" : ""));
			Thread.sleep(2000);
			if (success)
				consecutiveFailures = 0;
			else
				consecutiveFailures++;
		} while (consecutiveFailures < Config
				.getGlobalInt(TC.PROBE_MAX_CONSECUTIVE_FAILURES)
				&& runs < PROBE_MAX_RUNS);
		/**************** End of capacity probes *******************/
		System.out
				.println("capacity <= "
						+ Util.df(Math.max(capacity, load))
						+ " because"
						+ (capacity < threshold * load ? " response_rate was less than 95% of injected load"
								+ Util.df(load) + "/s; "
								: "")
						+ (latency > LATENCY_THRESHOLD ? " average_response_time="
								+ Util.df(latency)
								+ "ms"
								+ " >= "
								+ LATENCY_THRESHOLD + "ms;"
								: "")
						+ (loadIncreaseFactor < minLoadIncreaseFactor ? " capacity is within "
								+ Util.df((minLoadIncreaseFactor - 1) * 100)
								+ "% of next load level;"
								: "")
						+ (consecutiveFailures > Config
								.getGlobalInt(TC.PROBE_MAX_CONSECUTIVE_FAILURES) ? " too many consecutive failures;"
								: "")
						+ (runs >= PROBE_MAX_RUNS ? " reached limit of "
								+ PROBE_MAX_RUNS + " runs;" : ""));
		return responseRate;
	}

	protected static void twoPhaseTest(int numReqs, TESTPaxosClient[] clients)
			throws InterruptedException, JSONException, IOException,
			ExecutionException {
		// begin first run
		long t1 = System.currentTimeMillis();
		sendTestRequests(numReqs, clients, TOTAL_LOAD);
		waitForResponses(clients, t1);
		long t2 = System.currentTimeMillis();
		System.out.println("\n[run1]" + getAggregateOutput(t2 - t1));
		// end first run

		resetLatencyComputation(clients);
		Thread.sleep(1000);

		// begin second run
		t1 = System.currentTimeMillis();
		sendTestRequests(numReqs, clients, TOTAL_LOAD);
		waitForResponses(clients, t1);
		t2 = System.currentTimeMillis();
		printOutput(clients); // printed only after second
		System.out.println("\n[run2] " + getAggregateOutput(t2 - t1));
		// end second run
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
			TESTPaxosConfig.setConsoleHandler(Level.WARNING);
			NIOTransport.setUseSenderTask(Config
					.getGlobalBoolean(PC.USE_NIO_SENDER_TASK));
			TESTPaxosConfig.setDistribtedTest();

			TESTPaxosClient[] clients = TESTPaxosClient
					.setupClients(TESTPaxosConfig.getFromPaxosConfig(true));
			System.out.println(TESTPaxosConfig.getFromPaxosConfig(true));
			int numReqs = Config.getGlobalInt(TC.NUM_REQUESTS);

			// begin warmup run
			long t1 = System.currentTimeMillis();
			sendTestRequests(Math.min(numReqs, 10 * NUM_CLIENTS), clients,
					true, 10 * NUM_CLIENTS);
			waitForResponses(clients, t1);
			System.out.println("[success]");
			// end warmup run

			resetLatencyComputation(clients);
			Thread.sleep(1000);

			if (Config.getGlobalBoolean(TC.PROBE_CAPACITY))
				TESTPaxosClient.probeCapacity(
						Config.getGlobalDouble(TC.PROBE_INIT_LOAD), clients);
			else
				TESTPaxosClient.twoPhaseTest(numReqs, clients);

			for (TESTPaxosClient client : clients) {
				client.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
