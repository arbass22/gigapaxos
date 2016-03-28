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
package edu.umass.cs.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.nio.SSLDataProcessingWorker.SSL_MODES;
import edu.umass.cs.nio.interfaces.AddressMessenger;
import edu.umass.cs.nio.interfaces.InterfaceNIOTransport;
import edu.umass.cs.nio.interfaces.NodeConfig;
import edu.umass.cs.nio.interfaces.SSLMessenger;

/**
 * @author V. Arun
 * @param <NodeIDType>
 * 
 *            This class has support for retransmissions with exponential
 *            backoff. But you can't rely on this backoff for anything other
 *            than ephemeral traffic bursts. If you are overloaded, you are
 *            overloaded, so you must just reduce the load.
 */
public class JSONMessenger<NodeIDType> implements
		SSLMessenger<NodeIDType, JSONObject> {

	/**
	 * The JSON key for the time when the message was sent. Used only for
	 * instrumentation purposes by
	 * {@link AbstractJSONPacketDemultiplexer#handleMessage(JSONObject)
	 * AbstractPacketDemultiplexer.handleMessage}
	 */
	public static final String SENT_TIME = "SENT_TIME";
	private static final long RTX_DELAY = 1000; // ms
	private static final int BACKOFF_FACTOR = 2;

	private final InterfaceNIOTransport<NodeIDType, JSONObject> nioTransport;
	protected final ScheduledExecutorService execpool;
	private AddressMessenger<JSONObject> clientMessenger;
	private AddressMessenger<JSONObject> sslClientMessenger;

	private final MessageNIOTransport<NodeIDType, JSONObject>[] workers;

	private Logger log = NIOTransport.getLogger();

	/**
	 * @param niot
	 */
	/**
	 * @param niot
	 */
	public JSONMessenger(
			final InterfaceNIOTransport<NodeIDType, JSONObject> niot) {
		this(niot, 0);
	}

	/**
	 * @param niot
	 * @param numWorkers
	 */
	@SuppressWarnings("unchecked")
	public JSONMessenger(
			final InterfaceNIOTransport<NodeIDType, JSONObject> niot,
			int numWorkers) {
		// to not create thread pools unnecessarily
		if (niot instanceof JSONMessenger)
			this.execpool = ((JSONMessenger<NodeIDType>) niot).execpool;
		else
			this.execpool = Executors.newScheduledThreadPool(5,
					new ThreadFactory() {
						@Override
						public Thread newThread(Runnable r) {
							Thread thread = Executors.defaultThreadFactory()
									.newThread(r);
							thread.setName(JSONMessenger.class.getSimpleName()
									+ niot.getMyID() + thread.getName());
							return thread;
						}
					});
		nioTransport = (InterfaceNIOTransport<NodeIDType, JSONObject>) niot;

		this.workers = new MessageNIOTransport[numWorkers];
		for (int i = 0; i < workers.length; i++) {
			try {
				log.info((this + " starting worker with ssl mode " + this.nioTransport
						.getSSLMode()));
				this.workers[i] = new MessageNIOTransport<NodeIDType, JSONObject>(
						null, this.getNodeConfig(),
						this.nioTransport.getSSLMode());
				this.workers[i].setName(JSONMessenger.class.getSimpleName()
						+ niot.getMyID() + "_send_worker" + i);
			} catch (IOException e) {
				this.workers[i] = null;
				e.printStackTrace();
			}
		}
	}

	@Override
	public void send(GenericMessagingTask<NodeIDType, ?> mtask)
			throws IOException, JSONException {
		this.send(mtask, false);
	}

	/**
	 * Send returns void because it is the "ultimate" send. It will retransmit
	 * if necessary. It is inconvenient for senders to worry about
	 * retransmission anyway. We may need to retransmit despite using TCP-based
	 * NIO because NIO is designed to be non-blocking, so it may sometimes drop
	 * messages when asked to send but the channel is congested. We use the
	 * return value of NIO send to decide whether to retransmit.
	 */
	protected void send(GenericMessagingTask<NodeIDType, ?> mtask,
			boolean useWorkers) throws IOException, JSONException {
		if (mtask == null || mtask.recipients == null || mtask.msgs == null) {
			return;
		}
		IOException thrown = null;
		for (Object msg : mtask.msgs) {
			if (msg == null) {
				assert (false);
				continue;
			}
			String message = null;
			try {
				if (msg instanceof JSONObject) {
					message = ((JSONObject) (msg)).toString();
				} else
					// we no longer require msg to be JSON at all
					message = msg.toString();
			} catch (Exception je) {
				log.severe("JSONMessenger" + getMyID()
						+ " incurred exception while decoding: " + msg);
				throw (je);
			}
			byte[] msgBytes = message
					.getBytes(MessageNIOTransport.NIO_CHARSET_ENCODING);
			for (int r = 0; r < mtask.recipients.length; r++) {

				int sent = -1;
				try {
					// special case provision for InetSocketAddress
					sent = this.specialCaseSend(mtask.recipients[r], msgBytes,
							useWorkers);
				} catch (IOException e) {
					if ((e instanceof ClosedByInterruptException))
						throw e;
					e.printStackTrace();
					thrown = e;
					continue; // remaining sends might succeed
				}

				// check success or failure and react accordingly
				if (sent > 0) {
					log.log(Level.FINEST, "Node{0} sent to {1} ", new Object[] {
							this.nioTransport.getMyID(), mtask.recipients[r],
							message });
				} else if (sent == 0) {
					log.info("Node "
							+ this.nioTransport.getMyID()
							+ " messenger experiencing congestion, this is not disastrous (yet)");
					Retransmitter rtxTask = new Retransmitter(
							(mtask.recipients[r]), msgBytes, RTX_DELAY,
							useWorkers);
					// can't block here, so have to ignore returned future
					execpool.schedule(rtxTask, RTX_DELAY, TimeUnit.MILLISECONDS);
				} else {
					assert (sent == -1) : sent;
					log.severe("Node " + this.nioTransport.getMyID()
							+ " failed to send message to node "
							+ mtask.recipients[r] + ": " + msg);
				}
			}
		}
		if (thrown != null)
			throw thrown;
	}

	// Note: stops underlying NIOTransport as well.
	public void stop() {
		this.execpool.shutdown();
		this.nioTransport.stop();
		if (this.clientMessenger != null && this.clientMessenger != this
				&& this.clientMessenger instanceof InterfaceNIOTransport)
			((InterfaceNIOTransport<?, ?>) this.clientMessenger).stop();
		if (this.sslClientMessenger != null && this.sslClientMessenger != this
				&& this.sslClientMessenger instanceof InterfaceNIOTransport)
			((InterfaceNIOTransport<?, ?>) this.sslClientMessenger).stop();

		for (int i = 0; i < this.workers.length; i++)
			if (this.workers[i] != null
					&& !((MessageNIOTransport<?, ?>) workers[i]).isStopped()) {
				this.workers[i].stop();
			}
	}

	public String toString() {
		return JSONMessenger.class.getSimpleName() + getMyID();
	}

	@SuppressWarnings("unchecked")
	private int specialCaseSend(Object id, byte[] msgBytes, boolean useWorkers)
			throws IOException {
		if (id instanceof InetSocketAddress)
			return this.sendToAddress((InetSocketAddress) id, msgBytes);
		else
			return this.sendToID((NodeIDType) id, msgBytes, useWorkers);
	}

	/**
	 * We need this because NIO may drop messages when congested. Thankfully, it
	 * tells us when it does that. The task below exponentially backs off with
	 * each retransmission. We are probably doomed anyway if this class is
	 * invoked except rarely.
	 */
	private class Retransmitter implements Runnable {

		private final Object dest;
		private final byte[] msg;
		private final long delay;
		private final boolean useWorkers;

		Retransmitter(Object id, byte[] m, long d, boolean useWorkers) {
			this.dest = id;
			this.msg = m;
			this.delay = d;
			this.useWorkers = useWorkers;
		}

		@Override
		public void run() {
			int sent = 0;
			try {
				sent = specialCaseSend(this.dest, this.msg, this.useWorkers);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			} finally {
				if (sent < msg.toString().length() && sent != -1) {
					// nio can only send all or none, hence the assert
					assert (sent == 0);
					log.warning("Node "
							+ nioTransport.getMyID()
							+ "->"
							+ dest
							+ " messenger backing off under severe congestion, Hail Mary!");
					Retransmitter rtx = new Retransmitter(dest, msg, delay
							* BACKOFF_FACTOR, useWorkers);
					execpool.schedule(rtx, delay * BACKOFF_FACTOR,
							TimeUnit.MILLISECONDS);
				}
				// queue clogged and !isConnected, best to give up
				else if (sent == -1) {
					log.severe("Node "
							+ nioTransport.getMyID()
							+ "->"
							+ dest
							+ " messenger dropping message as destination unreachable: "
							+ msg);
				}
			}
		}
	}

	/**
	 * Sends jsonData to node id.
	 * 
	 * @param id
	 * @param jsonData
	 * @return Return value indicates the number of bytes sent. A value of -1
	 *         indicates an error.
	 * @throws java.io.IOException
	 */
	@Override
	public int sendToID(NodeIDType id, JSONObject jsonData) throws IOException {
		return this.nioTransport.sendToID(id, jsonData);
	}

	private int sendToID(NodeIDType id, byte[] msgBytes, boolean useWorkers)
			throws IOException {
		int i = (int) (Math.random() * (this.workers.length + 1));
		return (useWorkers && this.workers.length > 0
				&& i < this.workers.length && this.workers[i] != null) ? this.workers[i]
				.sendToID(id, msgBytes) : this.nioTransport.sendToID(id,
				msgBytes);
	}

	/**
	 * Sends jsonData to address.
	 * 
	 * @param address
	 * @param jsonData
	 * @return Refer {@link #sendToID(Object, JSONObject) sendToID(Object,
	 *         JSONObject)}.
	 * @throws IOException
	 */
	@Override
	public int sendToAddress(InetSocketAddress address, JSONObject jsonData)
			throws IOException {
		return this.nioTransport.sendToAddress(address, jsonData);
	}

	@Override
	public NodeIDType getMyID() {
		return this.nioTransport.getMyID();
	}

	/**
	 * @param pd
	 *            The supplied packet demultiplexer is appended to end of the
	 *            existing list. Messages will be processed by the first
	 *            demultiplexer that has registered for processing the
	 *            corresponding packet type and only by the first demultiplexer.
	 *            <p>
	 *            Note that there is no way to remove demultiplexers. All the
	 *            necessary packet demultiplexers must be determined at design
	 *            time. It is strongly recommended that all demultiplexers
	 *            process exclusive sets of packet types. Relying on the order
	 *            of chained demultiplexers is a bad idea.
	 */
	@Override
	public void addPacketDemultiplexer(AbstractPacketDemultiplexer<?> pd) {
		this.nioTransport.addPacketDemultiplexer(pd);
	}

	protected InterfaceNIOTransport<NodeIDType, JSONObject> getNIOTransport() {
		return this.nioTransport;
	}

	@Override
	public AddressMessenger<JSONObject> getClientMessenger() {
		return this.clientMessenger;
	}

	private AddressMessenger<JSONObject> getClientMessengerInternal() {
		return this.clientMessenger != null ? this.clientMessenger
				: (this.nioTransport instanceof JSONMessenger<?> ? ((JSONMessenger<?>) this.nioTransport)
						.getClientMessenger() : this.clientMessenger);
	}
	@Override
	public AddressMessenger<JSONObject> getSSLClientMessenger() {
		return this.sslClientMessenger;
	}
	
	private AddressMessenger<JSONObject> getSSLClientMessengerInternal() {
		return this.sslClientMessenger != null ? this.sslClientMessenger
				: (this.nioTransport instanceof JSONMessenger<?> ? ((JSONMessenger<?>) this.nioTransport)
						.getSSLClientMessenger() : this.sslClientMessenger);
	}


	@SuppressWarnings("unchecked")
	@Override
	public void setClientMessenger(AddressMessenger<?> clientMessenger) {
		if (this.clientMessenger != null)
			throw new IllegalStateException(
					"Can not change client messenger once set");
		this.clientMessenger = (AddressMessenger<JSONObject>) clientMessenger;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void setSSLClientMessenger(AddressMessenger<?> sslClientMessenger) {
		if (this.sslClientMessenger != null)
			throw new IllegalStateException(
					"Can not change client messenger once set");
		this.sslClientMessenger = (AddressMessenger<JSONObject>) sslClientMessenger;
	}

	/**
	 *
	 */
	public static class JSONObjectWrapper extends JSONObject {
		final Object obj;

		/**
		 * @param obj
		 */
		public JSONObjectWrapper(Object obj) {
			super();
			this.obj = obj;
		}

		public String toString() {
			return obj.toString();
		}
	}

	/**
	 * A hack that relies on the fact that NIO treats JSONObject as no different
	 * from any other object in that it invokes toString() and then getBytes(.)
	 * to serialize and send it over the network. It is necessary that the other
	 * end receiving this object be able to reconstruct it from a byte[],
	 * string, or JSONObject.
	 * 
	 * Automatically tries to use clientMessenger.
	 * 
	 * @param sockAddr
	 * @param message
	 * @param listenSocketAddress 
	 * @throws JSONException
	 * @throws IOException
	 */
	public void sendClient(InetSocketAddress sockAddr, Object message,
			InetSocketAddress listenSocketAddress) throws JSONException,
			IOException {
		AddressMessenger<JSONObject> msgr = this.getClientMessenger(listenSocketAddress);
		(msgr != null ? msgr : this).sendToAddress(sockAddr,
				new JSONObjectWrapper(message));
	}

	/**
	 * @param sockAddr
	 * @param message
	 * @throws JSONException
	 * @throws IOException
	 */
	public void sendClient(InetSocketAddress sockAddr, Object message)
			throws JSONException, IOException {
		this.sendClient(sockAddr, message, null);
	}

	@Override
	public NodeConfig<NodeIDType> getNodeConfig() {
		return this.nioTransport.getNodeConfig();
	}

	@Override
	public SSL_MODES getSSLMode() {
		return this.nioTransport.getSSLMode();
	}

	@Override
	public int sendToID(NodeIDType id, byte[] msg) throws IOException {
		return this.nioTransport.sendToID(id, msg);
	}

	@Override
	public int sendToAddress(InetSocketAddress isa, byte[] msg)
			throws IOException {
		return this.nioTransport.sendToAddress(isa, msg);
	}

	public boolean isStopped() {
		return this.nioTransport.isStopped();
	}

	@Override
	public boolean isDisconnected(NodeIDType node) {
		boolean disconnected = this.nioTransport.isDisconnected(node);
		if (this.workers != null)
			for (InterfaceNIOTransport<NodeIDType, JSONObject> niot : this.workers)
				disconnected = disconnected
						|| (niot != null && niot.isDisconnected(node));
		return disconnected;
	}

	@Override
	public AddressMessenger<JSONObject> getClientMessenger(
			InetSocketAddress listenSockAddr) {
		AddressMessenger<JSONObject> msgr = this.getClientMessengerInternal();
		if(listenSockAddr==null) return msgr; // default
		if (msgr instanceof InterfaceNIOTransport
				&& ((InterfaceNIOTransport<?, ?>) msgr)
						.getListeningSocketAddress().getPort()==(listenSockAddr.getPort()))
			return msgr;
		// else
		msgr = this.getSSLClientMessengerInternal();
		if (msgr instanceof InterfaceNIOTransport
				&& ((InterfaceNIOTransport<?, ?>) msgr)
						.getListeningSocketAddress().getPort()==(listenSockAddr.getPort()))
			return msgr;

		assert (this.getListeningSocketAddress().getPort()==(listenSockAddr.getPort())) : this
				.getListeningSocketAddress() + " != " + listenSockAddr;
		
		return this;
	}

	@Override
	public InetSocketAddress getListeningSocketAddress() {
		return this.nioTransport.getListeningSocketAddress();
	}

}
