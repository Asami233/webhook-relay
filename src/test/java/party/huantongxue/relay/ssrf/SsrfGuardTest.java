package party.huantongxue.relay.ssrf;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    /** 字面 IP 由 InetAddress 直接解析，不走 DNS；其他主机名一律当作解析不到。 */
    private static final SsrfGuard.Resolver LITERAL_ONLY = host -> {
        if (host.matches("[0-9a-fA-F.:x]+")) {
            return InetAddress.getAllByName(host);
        }
        throw new UnknownHostException(host);
    };

    private final SsrfGuard guard = new SsrfGuard(new SsrfProperties(List.of()), LITERAL_ONLY);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://127.1/", "http://2130706433/", "http://0.0.0.0/",
            "http://10.1.2.3/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://198.18.0.1/",
            "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[fc00::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:169.254.169.254]/", "http://[64:ff9b::a00:1]/",
            "http://[2002:a00:1::]/"
    })
    void blocksNonPublicAddressesInAnyNotation(String url) {
        SsrfGuard.Decision decision = guard.check(url);
        assertThat(decision.allowed()).as(url).isFalse();
        assertThat(decision.retryable()).as(url).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://93.184.215.14/hook", "http://8.8.8.8:8080/", "http://172.32.0.1/",
            "http://[2606:4700:4700::1111]/"})
    void allowsPublicAddresses(String url) {
        assertThat(guard.check(url).allowed()).as(url).isTrue();
    }

    @Test
    void unresolvableHostIsRetryableNotAllowed() {
        SsrfGuard.Decision decision = guard.check("https://does-not-exist.invalid/hook");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryable()).isTrue();
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsPrivate() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.215.14");
        InetAddress privateIp = InetAddress.getByName("10.0.0.5");
        SsrfGuard dualRecord = new SsrfGuard(new SsrfProperties(List.of()),
                host -> new InetAddress[] {publicIp, privateIp});
        assertThat(dualRecord.check("https://evil.example/hook").allowed()).isFalse();
    }

    @Test
    void dnsRebindingIsCaughtOnTheNextCheck() throws Exception {
        InetAddress[] answer = {InetAddress.getByName("93.184.215.14")};
        SsrfGuard rebinding = new SsrfGuard(new SsrfProperties(List.of()), host -> answer.clone());
        assertThat(rebinding.check("https://rebind.example/hook").allowed()).isTrue();
        answer[0] = InetAddress.getByName("169.254.169.254");
        assertThat(rebinding.check("https://rebind.example/hook").allowed()).isFalse();
    }

    @Test
    void sinkPrefixAllowsOnlyPrefixPlusId() {
        SsrfGuard withSink = new SsrfGuard(new SsrfProperties(List.of()), "http://127.0.0.1:8080/sink/", LITERAL_ONLY);
        assertThat(withSink.check("http://127.0.0.1:8080/sink/AbC-123_x").allowed()).isTrue();
        // 同主机其他路径、路径穿越、编码穿越、查询串、其他端口：都不在放行形状内，按 IP 检查被拒
        for (String url : List.of("http://127.0.0.1:8080/actuator/prometheus", "http://127.0.0.1:8080/sink/../actuator",
                "http://127.0.0.1:8080/sink/%2e%2e/actuator", "http://127.0.0.1:8080/sink/a/b",
                "http://127.0.0.1:8080/sink/a?x=1", "http://127.0.0.1:8080/sink/", "http://127.0.0.1:9000/sink/a")) {
            assertThat(withSink.check(url).allowed()).as(url).isFalse();
        }
    }

    @Test
    void allowListMatchesLiteralHostOnly() {
        SsrfGuard allowLoopbackIp = new SsrfGuard(new SsrfProperties(List.of("127.0.0.1")), LITERAL_ONLY);
        assertThat(allowLoopbackIp.check("http://127.0.0.1:9000/hook").allowed()).isTrue();
        assertThat(allowLoopbackIp.check("http://127.1:9000/hook").allowed()).isFalse();
        assertThat(allowLoopbackIp.check("http://[::1]:9000/hook").allowed()).isFalse();
    }
}
