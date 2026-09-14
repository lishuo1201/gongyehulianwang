import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Runtime readiness probe：T12，Compose在应用容器内执行此小程序，复用JRE而不额外安装curl。
 * 目标是配置中的readiness端点；只将HTTP 200作为就绪，失败返回非零供Docker记录unhealthy。
 * 本探针不读取寄存器、不检查单车业务状态，也不证明没有历史缺口；完整链路要看烟测和系统测试。
 */
public final class HealthCheck {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        int result = 0;
        try {
            connection = (HttpURLConnection) URI.create(args[0]).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Readiness returned HTTP " + connection.getResponseCode());
            }
        } catch (Exception failure) {
            // Fail visibly: 非200或连接失败必须使容器健康检查失败，不伪造UP。
            System.err.println("Readiness failed: " + failure.getClass().getSimpleName());
            result = 1;
        } finally {
            if (connection != null) connection.disconnect();
        }
        System.exit(result);
    }
}
