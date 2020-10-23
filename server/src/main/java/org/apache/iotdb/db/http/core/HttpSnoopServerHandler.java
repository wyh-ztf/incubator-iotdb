/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.http.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.apache.iotdb.db.http.constant.HttpConstant;
import org.apache.iotdb.db.http.router.HttpRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpSnoopServerHandler extends SimpleChannelInboundHandler<Object> {

  private FullHttpRequest request;

  private final HttpRouter httpRouter = new HttpRouter();

  private static final Logger logger = LoggerFactory.getLogger(HttpSnoopServerHandler.class);

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
    HttpResponseStatus status;
    if (msg instanceof HttpRequest) {
      this.request = (FullHttpRequest) msg;
      JsonElement result;
      try {
        result = httpRouter.route(request.method(), request.uri(),
            JsonParser.parseString(request.content().toString(CharsetUtil.UTF_8)));
        status = OK;
      } catch (Exception e) {
        result = new JsonObject();
        result.getAsJsonObject().addProperty(HttpConstant.ERROR_CLASS, e.getClass().toString());
        result.getAsJsonObject().addProperty(HttpConstant.ERROR, e.getMessage());
        status = INTERNAL_SERVER_ERROR;
      }

      writeResponse(ctx, result, status);
    }
  }

  private void writeResponse(ChannelHandlerContext ctx, JsonElement json,
      HttpResponseStatus status) {

    // Decide whether to close the connection or not.
    boolean keepAlive = HttpUtil.isKeepAlive(request);
    // Build the response object.
    FullHttpResponse response = new DefaultFullHttpResponse(
        HTTP_1_1, status,
        Unpooled.copiedBuffer(json.toString(), CharsetUtil.UTF_8));

    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");

    if (keepAlive) {
      // Add 'Content-Length' header only for a keep-alive connection.
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      // Add keep alive header as per:
      // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }

    // Write the response.
    ctx.write(response);

  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.debug(cause.getMessage());
    ctx.close();
  }
}
