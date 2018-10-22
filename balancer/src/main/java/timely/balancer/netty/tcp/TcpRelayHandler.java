package timely.balancer.netty.tcp;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import timely.api.request.MetricRequest;
import timely.api.request.TcpRequest;
import timely.balancer.connection.TimelyBalancedHost;
import timely.balancer.connection.tcp.TcpClientPool;
import timely.balancer.resolver.MetricResolver;
import timely.client.tcp.TcpClient;
import timely.netty.Constants;

import java.nio.charset.StandardCharsets;

public class TcpRelayHandler extends SimpleChannelInboundHandler<TcpRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(TcpRelayHandler.class);
    private static final String LOG_ERR_MSG = "Error storing put metric: {}";
    private static final String ERR_MSG = "Error storing put metric: ";

    private MetricResolver metricResolver;
    private TcpClientPool tcpClientPool;

    public TcpRelayHandler(MetricResolver metricResolver, TcpClientPool tcpClientPool) {
        this.metricResolver = metricResolver;
        this.tcpClientPool = tcpClientPool;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpRequest msg) throws Exception {
        LOG.trace("Received {}", msg);
        try {
            TimelyBalancedHost k;
            String line;
            if (msg instanceof MetricRequest) {
                String metricName = ((MetricRequest) msg).getMetric().getName();
                line = ((MetricRequest) msg).getLine();
                k = metricResolver.getHostPortKeyIngest(metricName);
            } else {
                // Version request
                line = "version";
                k = metricResolver.getHostPortKey(null);
            }
            TcpClient client = null;
            try {
                client = tcpClientPool.borrowObject(k);
                client.write(line + "\n");
                client.flush();
            } finally {
                if (client != null) {
                    tcpClientPool.returnObject(k, client);
                }
            }

        } catch (Exception e) {
            LOG.error(LOG_ERR_MSG, msg, e);
            ChannelFuture cf = ctx.writeAndFlush(Unpooled.copiedBuffer((ERR_MSG + e.getMessage() + "\n")
                    .getBytes(StandardCharsets.UTF_8)));
            if (!cf.isSuccess()) {
                LOG.error(Constants.ERR_WRITING_RESPONSE, cf.cause());
            }
        }
    }

}