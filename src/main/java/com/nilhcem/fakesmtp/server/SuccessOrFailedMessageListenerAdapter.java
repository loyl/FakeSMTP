/*
 * $Id: SuccessOrFailedMessageListenerAdapter.java 337 2009-06-29 19:20:58Z latchkey $
 * $URL: https://subethasmtp.googlecode.com/svn/trunk/src/main/java/org/subethamail/smtp/helper/SuccessOrFailedMessageListenerAdapter.java $
 */
package com.nilhcem.fakesmtp.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.TooMuchDataException;
import org.subethamail.smtp.helper.SimpleMessageListener;
import org.subethamail.smtp.io.DeferredFileOutputStream;

/**
 * MessageHandlerFactory implementation which adapts to a collection of
 * MessageListeners.  This allows us to preserve the old, convenient
 * interface.
 *
 * @author Jeff Schnitzer
 */
public class SuccessOrFailedMessageListenerAdapter implements MessageHandlerFactory
{
	/**
	 * 5 megs by default. The server will buffer incoming messages to disk
	 * when they hit this limit in the DATA received.
	 */
	private static int DEFAULT_DATA_DEFERRED_SIZE = 1024*1024*5;

	private Collection<SimpleMessageListener> listeners;
	private int dataDeferredSize;

	private Set<String> softBounces;

	/**
	 * Initializes this factory with a single listener.
	 *
	 * Default data deferred size is 5 megs.
	 */
	public SuccessOrFailedMessageListenerAdapter(SimpleMessageListener listener)
	{
		this(Collections.singleton(listener), DEFAULT_DATA_DEFERRED_SIZE);
	}

	/**
	 * Initializes this factory with the listeners.
	 *
	 * Default data deferred size is 5 megs.
	 */
	public SuccessOrFailedMessageListenerAdapter(Collection<SimpleMessageListener> listeners)
	{
		this(listeners, DEFAULT_DATA_DEFERRED_SIZE);
	}

	/**
	 * Initializes this factory with the listeners.
	 * @param dataDeferredSize The server will buffer
	 *        incoming messages to disk when they hit this limit in the
	 *        DATA received.
	 */
	public SuccessOrFailedMessageListenerAdapter(Collection<SimpleMessageListener> listeners, int dataDeferredSize)
	{
		this.listeners = listeners;
		this.dataDeferredSize = dataDeferredSize;
		this.softBounces = new HashSet<String>();
	}

	/* (non-Javadoc)
	 * @see org.subethamail.smtp.MessageHandlerFactory#create(org.subethamail.smtp.MessageContext)
	 */
	@Override
	public MessageHandler create(MessageContext ctx)
	{
		return new Handler(ctx);
	}

	/**
	 * Needed by this class to track which listeners need delivery.
	 */
	static class Delivery
	{
		SimpleMessageListener listener;
		public SimpleMessageListener getListener() { return this.listener; }

		String recipient;
		public String getRecipient() { return this.recipient; }

		public Delivery(SimpleMessageListener listener, String recipient)
		{
			this.listener = listener;
			this.recipient = recipient;
		}
	}

	/**
	 * Class which implements the actual handler interface.
	 */
	class Handler implements MessageHandler
	{
		MessageContext ctx;
		String from;
		List<Delivery> deliveries = new ArrayList<Delivery>();

		/** */
		public Handler(MessageContext ctx)
		{
			this.ctx = ctx;
		}

		/** */
		@Override
		public void from(String from) throws RejectException
		{
			this.from = from;
		}

		/** */
		@Override
		public void recipient(String recipient) throws RejectException
		{
			boolean addedListener = false;

			if (recipient.startsWith("a"))
				throw new RejectException(553, "<" + recipient + "> address unknown.");
			if (recipient.startsWith("b"))
				throw new RejectException(452, "Requested action not taken: insufficient system storage");
			if (recipient.startsWith("c")) {
				if (!softBounces.contains(recipient))
					// First try
					throw new RejectException(452, "Requested action not taken: insufficient system storage");
				else {
					// Second try
					softBounces.remove(recipient);
				}

			}
			if (recipient.contains("spamtrap"))
				throw new RejectException(550,
						"Your IP will be reported to the UCEPROTECT-Network - better watch out next time.");

			for (SimpleMessageListener listener: SuccessOrFailedMessageListenerAdapter.this.listeners)
			{
				if (listener.accept(this.from, recipient))
				{
					this.deliveries.add(new Delivery(listener, recipient));
					addedListener = true;
				}
			}

			if (!addedListener)
				throw new RejectException(553, "<" + recipient + "> address unknown.");
		}

		/** */
		@Override
		public void data(InputStream data) throws TooMuchDataException, IOException
		{
			if (this.deliveries.size() == 1)
			{
				Delivery delivery = this.deliveries.get(0);
				delivery.getListener().deliver(this.from, delivery.getRecipient(), data);
			}
			else
			{
				DeferredFileOutputStream dfos = new DeferredFileOutputStream(SuccessOrFailedMessageListenerAdapter.this.dataDeferredSize);

				try
				{
					int value;
					while ((value = data.read()) >= 0)
					{
						dfos.write(value);
					}

					for (Delivery delivery: this.deliveries)
					{
						delivery.getListener().deliver(this.from, delivery.getRecipient(), dfos.getInputStream());
					}
				}
				finally
				{
					dfos.close();
				}
			}
		}

		/** */
		@Override
		public void done()
		{
		}
	}
}
