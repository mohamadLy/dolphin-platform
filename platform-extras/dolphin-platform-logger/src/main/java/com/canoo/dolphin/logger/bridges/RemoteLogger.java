package com.canoo.dolphin.logger.bridges;

import com.canoo.dolphin.core.HttpConstants;
import com.canoo.dolphin.logger.DolphinLoggerConfiguration;
import com.canoo.dolphin.logger.DolphinLoggerUtils;
import com.canoo.dolphin.logger.impl.LogMessage;
import com.canoo.dolphin.logger.spi.DolphinLoggerBridge;
import com.canoo.platform.core.http.HttpURLConnectionFactory;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteLogger implements DolphinLoggerBridge {

    private final static Logger LOG = LoggerFactory.getLogger(RemoteLogger.class);

    private final URL remoteUrl;

    private final Executor executor;

    private final Gson gson = new Gson();

    private final HttpURLConnectionFactory connectionFactory;

    private Level level;

    private final AtomicBoolean remotingError = new AtomicBoolean(false);

    private final AtomicLong remotingErrorTime = new AtomicLong(0);

    private final BlockingQueue<LogMessage> messageBlockingQueue = new LinkedBlockingQueue();

    public RemoteLogger(final DolphinLoggerConfiguration configuration) {
        Objects.requireNonNull(configuration);
        this.remoteUrl = Objects.requireNonNull(configuration.getRemoteUrl());
        this.executor = Objects.requireNonNull(configuration.getRemoteLoggingExecutor());
        this.level = Objects.requireNonNull(configuration.getGlobalLevel());
        this.connectionFactory = Objects.requireNonNull(configuration.getConnectionFactory());

        executor.execute(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    try {
                        LOG.debug("{} messages in remote logging queue", messageBlockingQueue.size());
                        if (messageBlockingQueue.size() > configuration.getMaxRemotingQueueSize()) {
                            LOG.error("Overflow in remote logger message queue. Max elements: {} - Current: {}", configuration.getMaxRemotingQueueSize(), messageBlockingQueue.size());
                            while (messageBlockingQueue.size() > configuration.getMaxRemotingQueueSize() * (3.0 / 4.0)) {
                                messageBlockingQueue.take();
                            }
                        }
                        Thread.sleep(configuration.getRemotingQueueCheckSleepTime());
                    } catch (InterruptedException e) {
                        throw new RuntimeException("END!", e);
                    }
                }
            }
        });

        for (int i = 0; i < configuration.getParallelRequests(); i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            final LogMessage message = messageBlockingQueue.take();
                            try {
                                final HttpURLConnection conn = connectionFactory.create(remoteUrl);
                                conn.setDoOutput(true);
                                conn.setDoInput(true);
                                conn.setRequestProperty(HttpConstants.CONTENT_TYPE_HEADER, HttpConstants.JSON_MIME_TYPE);
                                conn.setRequestMethod(HttpConstants.POST_METHOD);

                                GelfMessage gelfMessage = new GelfMessage();
                                gelfMessage.setMessage(message.getMessage());
                                gelfMessage.setLoggerName(message.getLoggerName());
                                gelfMessage.setLogLevel(message.getLevel());
                                gelfMessage.setLogTimestamp(message.getTimestamp());
                                gelfMessage.setThreadName(message.getThreadName());
                                gelfMessage.setExceptionClass(message.getExceptionClass());
                                gelfMessage.setExceptionMessage(message.getExceptionMessage());
                                gelfMessage.setMarker(message.getMarker());
                                gelfMessage.setContext(message.getContext());
                                if(message.getTimeZone() != null) {
                                    gelfMessage.setTimeZone(message.getTimeZone().getID());
                                }



                                final String content = gson.toJson(gelfMessage);
                                OutputStream w = conn.getOutputStream();
                                w.write(content.getBytes(HttpConstants.CHARSET));
                                w.close();

                                //RESPONSE
                                int responseCode = conn.getResponseCode();
                                if (responseCode != HttpConstants.HTTP_OK && responseCode != HttpConstants.HTTP_ACCEPTED) {
                                    throw new IOException("Bad Request! status code " + responseCode);
                                }
                            } catch (Exception e) {
                                boolean added = messageBlockingQueue.offer(message);
                                if (!added) {
                                    LOG.error("Log message can not be added to Queue of RemoteLogger! " + messageBlockingQueue.size() + " waiting messages!", e);
                                }
                                if (!remotingError.get()) {
                                    remotingError.set(true);
                                    remotingErrorTime.set(System.currentTimeMillis());
                                    LOG.error("Error in RemoteLogger! " + messageBlockingQueue.size() + " waiting messages!", e);
                                } else if (remotingErrorTime.get() < System.currentTimeMillis() - configuration.getRemotingErrorWaitTime() * 2) {
                                    remotingErrorTime.set(System.currentTimeMillis());
                                    LOG.error("Error in RemoteLogger! " + messageBlockingQueue.size() + " waiting messages!");
                                }
                                Thread.sleep(configuration.getRemotingErrorWaitTime());
                            }
                        } catch (InterruptedException e) {
                            throw new RuntimeException("END!", e);
                        }
                    }
                }
            });
        }

    }

    @Override
    public void log(final LogMessage logMessage) {
        if (DolphinLoggerUtils.isLevelEnabled(this.level, logMessage.getLevel())) {
            boolean added = messageBlockingQueue.offer(logMessage);
            if (!added) {
                throw new RuntimeException("Log message can not be added to Queue of RemoteLogger! " + messageBlockingQueue.size() + " waiting messages!");
            }
        }
    }
}
