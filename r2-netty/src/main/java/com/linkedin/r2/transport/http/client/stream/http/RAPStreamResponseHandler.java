/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */

package com.linkedin.r2.transport.http.client.stream.http;

import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.message.stream.StreamResponseBuilder;
import com.linkedin.r2.transport.common.WireAttributeHelper;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.common.bridge.common.TransportResponseImpl;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.util.AttributeKey;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Netty pipeline handler which takes a complete received message and invokes the
 * user-specified callback.
 *
 * Note that an instance of this class needs to be stateless, since a single instance is used
 * in multiple ChannelPipelines simultaneously. The per-channel state is stored in Channel's
 * attachment and can be retrieved via {@code CALLBACK_ATTR_KEY}.
 *
 * @author Steven Ihde
 * @author Zhenkai Zhu
 * @version $Revision: $
 */
@ChannelHandler.Sharable
class RAPStreamResponseHandler extends SimpleChannelInboundHandler<StreamResponse>
{
  private static Logger LOG = LoggerFactory.getLogger(RAPStreamResponseHandler.class);

  public static final AttributeKey<TransportCallback<StreamResponse>> CALLBACK_ATTR_KEY
    = AttributeKey.valueOf("Callback");

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, StreamResponse response) throws Exception
  {
    final Map<String, String> headers = new HashMap<String, String>(response.getHeaders());
    final Map<String, String> wireAttrs =
      new HashMap<String, String>(WireAttributeHelper.removeWireAttributes(headers));

    final StreamResponse newResponse = new StreamResponseBuilder(response)
        .unsafeSetHeaders(headers)
                                          .build(response.getEntityStream());
    // In general there should always be a callback to handle a received message,
    // but it could have been removed due to a previous exception or closure on the
    // channel
    TransportCallback<StreamResponse> callback = ctx.channel().attr(CALLBACK_ATTR_KEY).getAndSet(null);
    if (callback != null)
    {
      LOG.debug("{}: handling a response", ctx.channel().remoteAddress());
      callback.onResponse(TransportResponseImpl.success(newResponse, wireAttrs));
    }
    else
    {
      LOG.debug("{}: dropped a response", ctx.channel().remoteAddress());
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
  {
    TransportCallback<StreamResponse> callback = ctx.channel().attr(CALLBACK_ATTR_KEY).getAndSet(null);
    if (callback != null)
    {
      LOG.debug(ctx.channel().remoteAddress() + ": exception on active channel", cause);
      callback.onResponse(TransportResponseImpl.<StreamResponse>error(
              HttpNettyStreamClient.toException(cause), Collections.<String,String>emptyMap()));
    }
    else
    {
      LOG.debug(ctx.channel().remoteAddress() + ": exception on potentially active channel", cause);
    }
    ctx.fireExceptionCaught(cause);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception
  {
    // XXX this seems a bit odd, but if the channel closed before downstream layers received a response, we
    // have to deal with that ourselves (it does not get turned into an exception by downstream
    // layers, even though some other protocol errors do)
    TransportCallback<StreamResponse> callback = ctx.channel().attr(CALLBACK_ATTR_KEY).getAndSet(null);
    if (callback != null)
    {
      LOG.debug("{}: active channel closed", ctx.channel().remoteAddress());
      callback.onResponse(TransportResponseImpl.<StreamResponse>error(new ClosedChannelException(),
          Collections.<String, String>emptyMap()));

    }
    else
    {
      LOG.debug("{}: potentially idle channel closed", ctx.channel().remoteAddress());
    }
    ctx.fireChannelInactive();
  }
}
