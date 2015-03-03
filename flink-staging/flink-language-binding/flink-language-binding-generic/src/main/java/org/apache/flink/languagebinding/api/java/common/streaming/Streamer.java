/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.flink.languagebinding.api.java.common.streaming;

import java.io.IOException;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import org.apache.flink.api.common.functions.AbstractRichFunction;
import org.apache.flink.configuration.Configuration;
import static org.apache.flink.languagebinding.api.java.common.PlanBinder.PLANBINDER_CONFIG_BCVAR_COUNT;
import static org.apache.flink.languagebinding.api.java.common.PlanBinder.PLANBINDER_CONFIG_BCVAR_NAME_PREFIX;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the basis for using an external process within a Java Flink operator. It contains logic to send and
 * receive data, while taking care of synchronization.
 */
public abstract class Streamer implements Serializable {
	protected static final Logger LOG = LoggerFactory.getLogger(Streamer.class);
	private static final int SIGNAL_BUFFER_REQUEST = 0;
	private static final int SIGNAL_BUFFER_REQUEST_G0 = -3;
	private static final int SIGNAL_BUFFER_REQUEST_G1 = -4;
	private static final int SIGNAL_FINISHED = -1;
	private static final int SIGNAL_ERROR = -2;
	private static final byte SIGNAL_LAST = 32;

	private final byte[] buffer = new byte[4];
	private DatagramPacket packet;
	protected InetAddress host;

	protected DatagramSocket socket;
	protected int port1;
	protected int port2;
	protected Sender sender;
	protected Receiver receiver;

	protected StringBuilder msg = new StringBuilder();

	protected final AbstractRichFunction function;

	public Streamer(AbstractRichFunction function) {
		this.function = function;
		sender = new Sender(function);
		receiver = new Receiver(function);
	}

	public void open() throws IOException {
		host = InetAddress.getByName("localhost");
		packet = new DatagramPacket(buffer, 0, 4);
		socket = new DatagramSocket(0, host);
		socket.setSoTimeout(10000);
		try {
			setupProcess();
			setupPorts();
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + function.getRuntimeContext().getTaskName() + " stopped responding." + msg);
		}
		socket.setSoTimeout(300000);
	}

	/**
	 * This method opens all required resources-
	 *
	 * @throws IOException
	 */
	public abstract void setupProcess() throws IOException;

	/**
	 * This method closes all previously opened resources.
	 *
	 * @throws IOException
	 */
	public void close() throws IOException {
		socket.close();
		sender.close();
		receiver.close();
	}

	/**
	 * Setups the required UDP-ports.The streamer requires two UDP-ports to send control-signals to, one each for
	 * reading/writing operations.
	 *
	 * @throws IOException
	 */
	private void setupPorts() throws IOException, SocketTimeoutException {
		socket.receive(new DatagramPacket(buffer, 0, 4));
		checkForError();
		port1 = getInt(buffer, 0);
		socket.receive(new DatagramPacket(buffer, 0, 4));
		checkForError();
		port2 = getInt(buffer, 0);
	}

	private void sendWriteNotification(int size, boolean hasNext) throws IOException {
		byte[] tmp = new byte[5];
		putInt(tmp, 0, size);
		tmp[4] = hasNext ? 0 : SIGNAL_LAST;
		socket.send(new DatagramPacket(tmp, 0, 5, host, port1));
	}

	private void sendReadConfirmation() throws IOException {
		socket.send(new DatagramPacket(new byte[1], 0, 1, host, port2));
	}

	private void checkForError() {
		if (getInt(buffer, 0) == -2) {
			try { //wait before terminating to ensure that the complete error message is printed
				Thread.sleep(2000);
			} catch (InterruptedException ex) {
			}
			throw new RuntimeException(
					"External process for task " + function.getRuntimeContext().getTaskName() + " terminated prematurely." + msg);
		}
	}

	/**
	 * Sends all broadcast-variables encoded in the configuration to the external process.
	 *
	 * @param config configuration object containing broadcast-variable count and names
	 * @throws IOException
	 */
	public final void sendBroadCastVariables(Configuration config) throws IOException {
		try {
			int broadcastCount = config.getInteger(PLANBINDER_CONFIG_BCVAR_COUNT, 0);

			String[] names = new String[broadcastCount];

			for (int x = 0; x < names.length; x++) {
				names[x] = config.getString(PLANBINDER_CONFIG_BCVAR_NAME_PREFIX + x, null);
			}

			socket.receive(packet);
			checkForError();
			int size = sender.sendRecord(broadcastCount);
			sendWriteNotification(size, false);

			for (String name : names) {
				Iterator bcv = function.getRuntimeContext().getBroadcastVariable(name).iterator();

				socket.receive(packet);
				checkForError();
				size = sender.sendRecord(name);
				sendWriteNotification(size, false);

				while (bcv.hasNext() || sender.hasRemaining(0)) {
					socket.receive(packet);
					checkForError();
					size = sender.sendBuffer(bcv, 0);
					sendWriteNotification(size, bcv.hasNext() || sender.hasRemaining(0));
				}
				sender.reset();
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + function.getRuntimeContext().getTaskName() + " stopped responding." + msg);
		}
	}

	/**
	 * Sends all values contained in the iterator to the external process and collects all results.
	 *
	 * @param i iterator
	 * @param c collector
	 * @throws IOException
	 */
	public final void streamBufferWithoutGroups(Iterator i, Collector c) throws IOException {
		try {
			int size;
			if (i.hasNext()) {
				while (true) {
					socket.receive(packet);
					int sig = getInt(buffer, 0);
					switch (sig) {
						case SIGNAL_BUFFER_REQUEST:
							if (i.hasNext() || sender.hasRemaining(0)) {
								size = sender.sendBuffer(i, 0);
								sendWriteNotification(size, sender.hasRemaining(0) || i.hasNext());
							}
							break;
						case SIGNAL_FINISHED:
							return;
						case SIGNAL_ERROR:
							try { //wait before terminating to ensure that the complete error message is printed
								Thread.sleep(2000);
							} catch (InterruptedException ex) {
							}
							throw new RuntimeException(
									"External process for task " + function.getRuntimeContext().getTaskName() + " terminated prematurely due to an error." + msg);
						default:
							receiver.collectBuffer(c, sig);
							sendReadConfirmation();
							break;
					}
				}
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + function.getRuntimeContext().getTaskName() + " stopped responding." + msg);
		}
	}

	/**
	 * Sends all values contained in both iterators to the external process and collects all results.
	 *
	 * @param i1 iterator
	 * @param i2 iterator
	 * @param c collector
	 * @throws IOException
	 */
	public final void streamBufferWithGroups(Iterator i1, Iterator i2, Collector c) throws IOException {
		try {
			int size;
			if (i1.hasNext() || i2.hasNext()) {
				while (true) {
					socket.receive(packet);
					int sig = getInt(buffer, 0);
					switch (sig) {
						case SIGNAL_BUFFER_REQUEST_G0:
							if (i1.hasNext() || sender.hasRemaining(0)) {
								size = sender.sendBuffer(i1, 0);
								sendWriteNotification(size, sender.hasRemaining(0) || i1.hasNext());
							}
							break;
						case SIGNAL_BUFFER_REQUEST_G1:
							if (i2.hasNext() || sender.hasRemaining(1)) {
								size = sender.sendBuffer(i2, 1);
								sendWriteNotification(size, sender.hasRemaining(1) || i2.hasNext());
							}
							break;
						case SIGNAL_FINISHED:
							return;
						case SIGNAL_ERROR:
							try { //wait before terminating to ensure that the complete error message is printed
								Thread.sleep(2000);
							} catch (InterruptedException ex) {
							}
							throw new RuntimeException(
									"External process for task " + function.getRuntimeContext().getTaskName() + " terminated prematurely due to an error." + msg);
						default:
							receiver.collectBuffer(c, sig);
							sendReadConfirmation();
							break;
					}
				}
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + function.getRuntimeContext().getTaskName() + " stopped responding." + msg);
		}
	}

	protected final static int getInt(byte[] array, int offset) {
		return (array[offset] << 24) | (array[offset + 1] & 0xff) << 16 | (array[offset + 2] & 0xff) << 8 | (array[offset + 3] & 0xff);
	}

	protected final static void putInt(byte[] array, int offset, int value) {
		array[offset] = (byte) (value >> 24);
		array[offset + 1] = (byte) (value >> 16);
		array[offset + 2] = (byte) (value >> 8);
		array[offset + 3] = (byte) (value);
	}

}