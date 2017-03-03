/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.server.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.util.ReferenceCountUtil;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticationException;
import org.apache.tinkerpop.gremlin.server.auth.Authenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialGraphTokens.PROPERTY_PASSWORD;
import static org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialGraphTokens.PROPERTY_USERNAME;

/**
 * Implements basic HTTP authentication for use with the {@link HttpGremlinEndpointHandler} and HTTP based API calls.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class HttpBasicAuthenticationHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HttpBasicAuthenticationHandler.class);
    private static final Logger auditLogger = LoggerFactory.getLogger(GremlinServer.AUDIT_LOGGER_NAME);
    private final Authenticator authenticator;
    private final Settings.AuthenticationSettings authenticationSettings;

    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public HttpBasicAuthenticationHandler(final Authenticator authenticator,
                                          final Settings.AuthenticationSettings authenticationSettings) {
        this.authenticator = authenticator;
        this.authenticationSettings = authenticationSettings;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof FullHttpMessage) {
            final FullHttpMessage request = (FullHttpMessage) msg;
            if (!request.headers().contains("Authorization")) {
                sendError(ctx, msg);
                return;
            }

            // strip off "Basic " from the Authorization header (RFC 2617)
            final String basic = "Basic ";
            final String authorizationHeader = request.headers().get("Authorization");
            if (!authorizationHeader.startsWith(basic)) {
                sendError(ctx, msg);
                return;
            }
            byte[] decodedUserPass = null;
            try {
                final String encodedUserPass = authorizationHeader.substring(basic.length());
                decodedUserPass = decoder.decode(encodedUserPass);
            } catch (IndexOutOfBoundsException iae) {
                sendError(ctx, msg);
                return;
            } catch (IllegalArgumentException iae) {
                sendError(ctx, msg);
                return;
            }
            final String authorization = new String(decodedUserPass, Charset.forName("UTF-8"));
            final String[] split = authorization.split(":");
            if (split.length != 2) {
                sendError(ctx, msg);
                return;
            }

            final Map<String,String> credentials = new HashMap<>();
            credentials.put(PROPERTY_USERNAME, split[0]);
            credentials.put(PROPERTY_PASSWORD, split[1]);

            try {
                authenticator.authenticate(credentials);
                ctx.fireChannelRead(request);

                // User name logged with the remote socket address and authenticator classname for audit logging
                if (authenticationSettings.enableAuditLog) {
                    String address = ctx.channel().remoteAddress().toString();
                    if (address.startsWith("/") && address.length() > 1) address = address.substring(1);
                    final String[] authClassParts = authenticator.getClass().toString().split("[.]");
                    auditLogger.info("User {} with address {} authenticated by {}",
                            credentials.get(PROPERTY_USERNAME), address, authClassParts[authClassParts.length - 1]);
                }
            } catch (AuthenticationException ae) {
                sendError(ctx, msg);
            }
        }
    }

    private void sendError(final ChannelHandlerContext ctx, final Object msg) {
        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, UNAUTHORIZED)).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.release(msg);
    }
}
