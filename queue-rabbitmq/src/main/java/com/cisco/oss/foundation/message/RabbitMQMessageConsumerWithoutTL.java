/*
 * Copyright 2015 Cisco Systems, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.cisco.oss.foundation.message;

import com.cisco.oss.foundation.configuration.ConfigUtil;
import com.cisco.oss.foundation.configuration.ConfigurationFactory;
import com.cisco.oss.foundation.flowcontext.FlowContextFactory;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.QueueingConsumer;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A RabbitMQ consumer wrapper. As appose to RabbitMQMessageConsumer where ThreadLocal is used,
 * here channel is defined per class instance and should be use by a single thread
 * Created by Merav Tanami on 20/12/2017.
 */
public class RabbitMQMessageConsumerWithoutTL implements MessageConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQMessageConsumerWithoutTL.class);
    public static final String DLQ = "DLQ";
    private String consumerName = "N/A";
    private Configuration configuration = ConfigurationFactory.getConfiguration();

    private String queueName = "";
    private QueueingConsumer consumer = null;
    private AtomicBoolean isRegistered = new AtomicBoolean(false);
    private String consumerTag = null;

    RabbitMQMessageConsumerWithoutTL(String consumerName) {
        try {
            this.consumerName = consumerName;
            Configuration subset = configuration.subset(consumerName);
            queueName = subset.getString("queue.name");

            String filter = subset.getString("queue.filter", "");
            boolean isAutoDelete = subset.getBoolean("queue.isAutoDelete", false);
            boolean isDurable = subset.getBoolean("queue.isDurable", true);
            boolean isSubscription = subset.getBoolean("queue.isSubscription", false);
            long expiration = subset.getLong("queue.expiration", 1800000);
            long maxLength = subset.getLong("queue.maxLength", -1);
            boolean deadLetterIsEnabled = subset.getBoolean("queue.deadLetterIsEnabled", true);
            String deadLetterExchangeName = subset.getString("queue.deadLetterExchangeName", DLQ);
            String subscribedTo = isSubscription ? subset.getString("queue.subscribedTo", "") : queueName;
            String exchangeType = subset.getString("queue.exchangeType", isSubscription ? "topic" : "direct");
            boolean isExclusive = subset.getBoolean("queue.isExclusive", false);
            try {
                RabbitMQMessagingFactory.INIT_LATCH.await();
            } catch (InterruptedException e) {
                LOGGER.error("error waiting for init to finish: " + e);
            }
            Channel channel = RabbitMQMessagingFactory.createChannelWithoutTL();
            channel.exchangeDeclare(subscribedTo, exchangeType, isDurable, false, false, null);

            Map<String, Object> args = new HashMap<String, Object>();

            if (maxLength > 0) {
                args.put("x-max-length", maxLength);
            }

            if (expiration > 0) {
                args.put("x-message-ttl", expiration);
            }


            if (deadLetterIsEnabled) {
                channel.exchangeDeclare(deadLetterExchangeName, exchangeType, isDurable, false, false, null);
                args.put("x-dead-letter-exchange", deadLetterExchangeName);
            }

            String queue = channel.queueDeclare(queueName, isDurable, isExclusive, isAutoDelete, args).getQueue();

            Map<String, String> filters = ConfigUtil.parseSimpleArrayAsMap(consumerName + ".queue.filters");
            if (filters != null && !filters.isEmpty()) {
                for (String routingKey : filters.values()) {
                    channel.queueBind(queue, subscribedTo, routingKey);
                }
            } else {
                channel.queueBind(queue, subscribedTo, "#");
            }

            consumer = new QueueingConsumer(channel);
//            channel.basicConsume(queueName, true, consumer);
            LOGGER.info("created rabbitmq consumer: {} on exchange: {}, queue-name: {} channel number {}",
                    consumerName, subscribedTo, queueName,channel.getChannelNumber());
        } catch (IOException e) {
            throw new QueueException("Can't create consumer: " + e, e);
        }

    }

    @Override
    public Message receive() {

        try {
            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
            GetResponse getResponse = new GetResponse(delivery.getEnvelope(), delivery.getProperties(), delivery.getBody(), 0);
            RabbitMQMessage rabbitMQMessage = new RabbitMQMessage(getResponse, "");
            return rabbitMQMessage;
        } catch (InterruptedException e) {
            throw new QueueException("can't get new message: " + e, e);
        }

    }

    @Override
    public Message receive(long timeout) {
        try {
            QueueingConsumer.Delivery delivery = consumer.nextDelivery(timeout);
            GetResponse getResponse = new GetResponse(delivery.getEnvelope(), delivery.getProperties(), delivery.getBody(), 0);
            RabbitMQMessage rabbitMQMessage = new RabbitMQMessage(getResponse, "");
            return rabbitMQMessage;
        } catch (InterruptedException e) {
            throw new QueueException("can't get new message: " + e, e);
        }
    }

    public void registerMessageHandler(MessageHandler messageHandler, boolean autoAck) {
        if (isRegistered.compareAndSet(false, true)) {
            try {
                if (messageHandler instanceof AbstractRabbitMQMessageHandler) {
                    String consumerTag = FlowContextFactory.getFlowContext() != null ? FlowContextFactory.getFlowContext().getUniqueId() : "N/A";
                    Channel channel = consumer.getChannel();
                    ((AbstractRabbitMQMessageHandler) messageHandler).setChannelNumber(channel.getChannelNumber());
                    this.consumerTag = channel.basicConsume(queueName, autoAck, consumerTag, (Consumer) messageHandler);
                } else {
                    throw new IllegalArgumentException("Using RabbitMQ consumerThreadLocal you must provide a valid RabbitMQ massage handler");
                }

            } catch (IOException e) {
//            LOGGER.error("can't register a MessageHandler: {}", e);
                throw new QueueException("can't register a MessageHandler: " + e, e);
            }
        }
    }

    public void ackMessage(Integer channelNumber, Long deliveryTag) {
        RabbitMQMessagingFactory.ackMessage(channelNumber, deliveryTag);
    }

    public void nackMessage(Integer channelNumber, Long deliveryTag) {
        RabbitMQMessagingFactory.nackMessage(channelNumber, deliveryTag);
    }

    @Override
    public void registerMessageHandler(MessageHandler messageHandler) {
        registerMessageHandler(messageHandler, true);
    }

    @Override
    public boolean unRregisterMessageHandler() {
        boolean success = false;
        if (isRegistered.compareAndSet(true,false)) {
            if (consumer != null) {
                try {
                    Channel channel = consumer.getChannel();
                    channel.basicCancel(this.consumerTag);
                    success = true;
                } catch (IOException e) {
                    LOGGER.error("can't unregsiter the handler. reaon: {}", e, e);
                }
            }
        }else{
            LOGGER.warn("can't unregister as there is no handler currently registered");
        }
        return success;

    }

    @Override
    public void close() {
        Channel channel = consumer.getChannel();
        RabbitMQMessagingFactory.consumers.remove(consumerName);
        RabbitMQMessagingFactory.closeChannelAndRemoveFromMap(channel, "consumer was closed");
    }

    public String getQueueName() {
        return queueName;
    }
}
