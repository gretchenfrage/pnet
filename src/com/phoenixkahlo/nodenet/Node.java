package com.phoenixkahlo.nodenet;

import com.phoenixkahlo.nodenet.proxy.Proxy;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Representation of a node in the network.
 */
public interface Node {

	/**
	 * Receive an object from this node. This will block until an object is
	 * received. If a custom receiver is set, this method will block forever.
	 */
	Object receive() throws DisconnectionException;

	Optional<Object> receiveWithin(long millis) throws DisconnectionException;

	@SuppressWarnings("unchecked")
	default <E> E receive(Class<E> type) throws DisconnectionException, ProtocolViolationException {
		Object received = receive();
		if (type.isAssignableFrom(received.getClass()))
			return (E) received;
		else
			throw new ProtocolViolationException(received + " not instance of " + type);
	}

	@SuppressWarnings("unchecked")
	default <E> Optional<E> receiveWithin(Class<E> type, long millis) throws DisconnectionException, ProtocolViolationException {
		Optional<Object> received = receiveWithin(millis);
		if (!received.isPresent())
			return Optional.empty();
		if (type.isAssignableFrom(received.get().getClass()))
			return (Optional<E>) received;
		else
			throw new ProtocolViolationException(received.get() + " not instance of " + type);
	}

	default <E> Proxy<E> receiveProxy(Class<E> type) throws DisconnectionException, ProtocolViolationException {
		Proxy<?> proxy = receive(Proxy.class);
		return proxy.cast(type);
	}

	@SuppressWarnings("unchecked")
	default <E> Optional<Proxy<E>> receivedProxyWithin(Class<E> type, long millis) throws DisconnectionException, ProtocolViolationException {
		Optional<Proxy> proxy = receiveWithin(Proxy.class, millis);
		if (proxy.isPresent())
			return Optional.of(proxy.get().cast(type));
		else
			return Optional.empty();
	}
	
	/**
	 * Set the handler for objects received from this node. This will change it
	 * from the default of making them available to the receive method, causing
	 * that method to block forever.
	 */
	void setReceiver(Consumer<Object> receiver);

	default void setReceiver(Consumer<Object> receiver, boolean launchNewThread) {
		if (launchNewThread)
			setReceiver(object -> new Thread(() -> receiver.accept(object)).start());
		else
			setReceiver(receiver);
	}

	/**
	 * Set the handler for objects received from this node to the default, such
	 * that they become available to the receive method.
	 */
	void resetReceiver();

	/**
	 * Send an object to this node.
	 */
	void send(Object object) throws DisconnectionException;

	/**
	 * Send an object to this node, and wait for confirmation of it being
	 * successfully received. In the rare event that transmission fails, send a
	 * TransmissionException.
	 */
	void sendAndConfirm(Object object) throws DisconnectionException, TransmissionException;

	/**
	 * Sever any DatagramStream with this node.
	 */
	boolean unlink();

	/**
	 * @return if the node is completely disconnected and all correspondences
	 *         will fail.
	 */
	boolean isDisconnected();

	/**
	 * Provide a listener to be invoked upon the disconnection of this node, or
	 * immediately if the node is already disconnected.
	 */
	void listenForDisconnect(Runnable listener);

	NodeAddress getAddress();

}
