package party.huantongxue.relay.delivery;

import java.lang.management.ManagementFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import party.huantongxue.relay.metrics.RelayMetrics;
import party.huantongxue.relay.security.SecretCipher;
import party.huantongxue.relay.ssrf.SsrfGuard;

@Configuration(proxyBeanMethods = false)
public class DeliveryConfig {

    @Bean
    DeliverySender deliverySender(DeliveryProperties props, SecretCipher cipher) {
        return new DeliverySender(props, cipher);
    }

    /** relay.delivery.enabled=false 时不创建：实例只负责接收（测试也用这个开关自己控制 worker）。 */
    @Bean
    @ConditionalOnProperty(name = "relay.delivery.enabled", havingValue = "true", matchIfMissing = true)
    DeliveryWorkerLifecycle deliveryWorkerLifecycle(DeliveryStore store, DeliverySender sender, SsrfGuard ssrf,
                                                    EndpointGuard guard, RelayMetrics metrics,
                                                    DeliveryProperties props) {
        // 实例名只用于日志；默认是 JVM 名（pid@host）
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return new DeliveryWorkerLifecycle(new DeliveryWorker(name, store, sender, ssrf, guard, metrics, props));
    }

    /**
     * 挂到 Spring 生命周期：上下文就绪后开始抢任务；停机时先于数据源关闭被调用，
     * 停抢、等在途投递写回结果，再交给后续的 Web 服务器与连接池关闭。
     */
    static final class DeliveryWorkerLifecycle implements SmartLifecycle {

        private final DeliveryWorker worker;

        DeliveryWorkerLifecycle(DeliveryWorker worker) {
            this.worker = worker;
        }

        @Override
        public void start() {
            worker.start();
        }

        @Override
        public void stop() {
            worker.stop();
        }

        @Override
        public boolean isRunning() {
            return worker.isRunning();
        }

        /** 本实例接收的事件：直接唤醒投递循环，低负载时端到端延迟不再受轮询间隔影响。 */
        @EventListener
        public void onDeliveriesCreated(DeliveriesCreated event) {
            worker.wakeup();
        }
    }
}
