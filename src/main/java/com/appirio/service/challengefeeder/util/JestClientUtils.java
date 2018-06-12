/*
 * Copyright (C) 2017 TopCoder Inc., All Rights Reserved.
 */
package com.appirio.service.challengefeeder.util;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Bulk;
import io.searchbox.core.Delete;
import io.searchbox.core.Index;
import io.searchbox.core.Bulk.Builder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;

import vc.inreach.aws.request.AWSSigner;
import vc.inreach.aws.request.AWSSigningRequestInterceptor;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.appirio.service.challengefeeder.api.IdentifiableData;
import com.appirio.service.challengefeeder.config.JestClientConfiguration;
import com.appirio.service.challengefeeder.dto.FeederParam;
import com.google.common.base.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * The jest client utils.
 * 
 * Version 1.1 - Topcoder - Populate Marathon Match Related Data Into Challenge Model In Elasticsearch v1.0
 * - add pushFeeders method which can be reused to push challenge, marathon and single round matches feeders.
 * 
 * 
 * @author TCSCODER
 * @version 1.1 
 */
public class JestClientUtils {

    /**
     * The Gson instance
     */
    private static final Gson GSON_INSTANCE = new GsonBuilder().registerTypeAdapter(Map.class, new MapDeserializer())
            .registerTypeAdapter(Date.class, new DateSerializer()).registerTypeAdapter(List.class, new ListDeserializer()).create();
    
    /**
     * Push feeders
     *
     * @param jestClient the jestClient to use
     * @param param the param to use
     * @param feeders the feeders to use
     * @throws IOException if any io error occurs
     */
    public static void pushFeeders(JestClient jestClient, FeederParam param, List<? extends IdentifiableData> feeders) throws IOException {
        // first delete the index and then create it
        Builder builder = new Bulk.Builder();
        for (IdentifiableData data : feeders) {
            builder.addAction(new Delete.Builder(data.getId().toString()).index(param.getIndex()).type(param.getType()).build());
            builder.addAction(new Index.Builder(data).index(param.getIndex()).type(param.getType()).id(data.getId().toString()).build());
        }
        Bulk bulk = builder.build();

        jestClient.execute(bulk);
    }

    /**
     * Create JestClient instance
     * 
     * @param jestClientConfig the jest client configuration
     * @return the JestClient instance
     */
    public static JestClient get(JestClientConfiguration jestClientConfig) {
        final JestClientFactory factory = jestClientConfig.isAwsSigningEnabled() ? getAWSJestClientFactory(jestClientConfig) : new JestClientFactory();

        factory.setHttpClientConfig(new HttpClientConfig.Builder(jestClientConfig.getElasticSearchUrl()).multiThreaded(true)
                .connTimeout(jestClientConfig.getConnTimeout()).readTimeout(jestClientConfig.getReadTimeout())
                .maxTotalConnection(jestClientConfig.getMaxTotalConnections()).discoveryEnabled(false).gson(GSON_INSTANCE).build());

        return factory.getObject();
    }

    /**
     * Create JestClientFactory instance with AWS signing interceptor
     * 
     * @param jestClientConfig the jest client configuration
     * @return the JestClientFactory instance with AWS signing interceptor
     */
    private static JestClientFactory getAWSJestClientFactory(JestClientConfiguration jestClientConfig) {
        DefaultAWSCredentialsProviderChain awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final Supplier<LocalDateTime> clock = () -> LocalDateTime.now(ZoneOffset.UTC);
        final AWSSigner awsSigner = new AWSSigner(awsCredentialsProvider, jestClientConfig.getAwsRegion(), jestClientConfig.getAwsService(), clock);
        final AWSSigningRequestInterceptor requestInterceptor = new AWSSigningRequestInterceptor(awsSigner);

        final JestClientFactory factory = new JestClientFactory() {
            /**
             * Intercept the http client with AWS signing request interceptor
             * 
             * @param builder the http client builder
             */
            @Override
            protected HttpClientBuilder configureHttpClient(HttpClientBuilder builder) {
                builder.addInterceptorLast(requestInterceptor);
                return builder;
            }

            /**
             * Intercept the async http client with AWS signing request
             * interceptor
             * 
             * @param builder the async http client builder
             */
            @Override
            protected HttpAsyncClientBuilder configureHttpClient(HttpAsyncClientBuilder builder) {
                builder.addInterceptorLast(requestInterceptor);
                return builder;
            }
        };

        return factory;
    }

    /**
     * The custom Map deserializer to fix the Gson parsing long/integer as double
     * bug
     * 
     * @author TCSCODER
     * @version 1.0
     */
    private static class MapDeserializer implements JsonDeserializer<Map<String, Object>> {

        /**
         * Empty constructor
         */
        public MapDeserializer() {
        }

        /**
         * The custom deserialization implementation
         * 
         * @param json the json to deserialize
         * @param typeOfT the type
         * @param context the context
         * 
         * @throws JsonParseException if the deserialization fails
         */
        public Map<String, Object> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            Map<String, Object> m = new HashMap<>();
            JsonObject jo = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> mx : jo.entrySet()) {
                String key = mx.getKey();
                JsonElement v = mx.getValue();
                if (v.isJsonArray()) {
                    m.put(key, GSON_INSTANCE.fromJson(v, List.class));
                } else if (v.isJsonPrimitive()) {
                    Number num = null;
                    ParsePosition position = new ParsePosition(0);
                    String vString = v.getAsString();
                    try {
                        num = NumberFormat.getInstance(Locale.ROOT).parse(vString, position);
                    } catch (Exception e) {
                    }

                    if (position.getErrorIndex() < 0 && vString.length() == position.getIndex()) {
                        if (num != null) {
                            m.put(key, num);
                            continue;
                        }
                    }
                    JsonPrimitive prim = v.getAsJsonPrimitive();

                    if (prim.isBoolean()) {
                        m.put(key, prim.getAsBoolean());
                    } else if (prim.isString()) {
                        m.put(key, prim.getAsString());
                    } else {
                        m.put(key, null);
                    }

                } else if (v.isJsonObject()) {
                    m.put(key, GSON_INSTANCE.fromJson(v, Map.class));
                }

            }
            return m;
        }
    }

    /**
     * The custom List deserializer to fix the Gson parsing long/integer as double
     * bug
     * 
     * @author TCSCODER
     * @version 1.0
     */
    private static class ListDeserializer implements JsonDeserializer<List<Object>> {

        /**
         * Empty constructor
         */
        ListDeserializer() {
        }

        /**
         * The custom deserialization implementation
         * 
         * @param json the json to deserialize
         * @param typeOfT the type
         * @param context the context
         * 
         * @throws JsonParseException if the deserialization fails
         */
        public List<Object> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            List<Object> m = new ArrayList<>();
            JsonArray arr = json.getAsJsonArray();
            for (JsonElement jsonElement : arr) {
                if (jsonElement.isJsonObject()) {
                    m.add(GSON_INSTANCE.fromJson(jsonElement, Map.class));
                } else if (jsonElement.isJsonArray()) {
                    m.add(GSON_INSTANCE.fromJson(jsonElement, List.class));
                } else if (jsonElement.isJsonPrimitive()) {
                    Number num = null;
                    try {
                        num = NumberFormat.getInstance().parse(jsonElement.getAsString());
                    } catch (Exception e) {
                    }
                    if (num != null) {
                        m.add(num);
                        continue;
                    }

                    JsonPrimitive prim = jsonElement.getAsJsonPrimitive();
                    if (prim.isBoolean()) {
                        m.add(prim.getAsBoolean());
                    } else if (prim.isString()) {
                        m.add(prim.getAsString());
                    } else {
                        m.add(null);
                    }
                }
            }
            return m;
        }
    }

    /**
     * The DateSerializer used to convert date to string
     * 
     * @author TCSCODER
     * @version 1.0
     */
    private static class DateSerializer implements JsonSerializer<Date> {

        /**
         * Empty constructor
         */
        public DateSerializer() {
        }

        /**
         * Serialize the date
         *
         * @param date the date to use
         * @param type the type to use
         * @param context the context to use
         * @return the JsonElement result
         */
        @Override
        public JsonElement serialize(Date date, Type type, JsonSerializationContext context) {
            DateFormat newDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            newDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            JsonElement element = new JsonPrimitive(newDateFormat.format(date));
            return element;
        }
    }
}
