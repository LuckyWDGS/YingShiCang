package com.github.catvod.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.Test;

public class NetTraceTest {

    @Test
    public void hostsBecomeCoarseLabels() {
        assertEquals("quark", NetTrace.label("drive-pc.quark.cn"));
        assertEquals("baidu", NetTrace.label("pan.baidu.com"));
        assertEquals("ali", NetTrace.label("api.alipan.com"));
        assertEquals("other", NetTrace.label("my-private-source.example"));
        assertEquals("other", NetTrace.label("notquark.cn"));
        assertEquals("ip", NetTrace.label("10.0.0.8"));
        assertEquals("local", NetTrace.label("127.0.0.1"));
    }

    @Test
    public void reasonsNeverCarryTheMessage() {
        assertEquals("IOException(eos)", NetTrace.reason(new IOException("unexpected end of stream on https://secret.example/?token=1")));
        assertEquals("SocketTimeoutException(timeout)", NetTrace.reason(new SocketTimeoutException("timeout")));
        assertEquals("IOException(other)", NetTrace.reason(new IOException("https://secret.example/?token=1")));
        assertEquals("IOException", NetTrace.reason(new IOException()));
    }

    @Test
    public void pluginMessagesKeepTheirReasonWithoutTitlesAddressesOrIds() {
        assertEquals("IOException(分享已失效)", NetTrace.reason(new IOException("分享已失效")));
        assertEquals("IOException(容量不足_请清理网盘)", NetTrace.reason(new IOException("容量不足 请清理网盘")));
        assertEquals("文件…不存在", NetTrace.readable("文件《希望》不存在"));
        assertEquals("链接失效：…", NetTrace.readable("链接失效：https://pan.quark.cn/s/abc"));
        assertEquals("令牌_…_已过期", NetTrace.readable("令牌 abcdef123456 已过期"));
        assertEquals("账号…未登录", NetTrace.readable("账号13800138000未登录"));
        assertEquals("account_expired", NetTrace.readable("account expired"));
        assertEquals("Not_logged_in,_status_403", NetTrace.readable("Not logged in, status 403"));
        assertEquals("share_…_not_found_for_…", NetTrace.readable("share abc123def456 not found for user@example.com"));
        assertEquals("bad_param_…", NetTrace.readable("bad param fid=12"));
        assertEquals("other", NetTrace.readable("https://secret.example/?token=1"));
        assertEquals(61, NetTrace.readable("很长的错误原因".repeat(20)).length());
    }

    @Test
    public void emptyRecordingSummarizesNothing() {
        assertEquals("net=0 fail=0", NetTrace.begin().end());
    }

    @Test
    public void failedLoopbackCallsNameThePortsTried() {
        NetTrace.Recording recording = NetTrace.begin();
        recording.add(new NetTrace.Entry("quark", -1, 208, 3, 81, "h2", "200", false));
        recording.add(new NetTrace.Entry("local", 9978, 7, -1, 2, "http/1.1", "200", false));
        recording.add(new NetTrace.Entry("local", 6677, 101, -1, 15, "http/1.1", "IOException(eos)", true));
        for (int port = 6678; port <= 6999; port++) recording.add(new NetTrace.Entry("local", port, 20, -1, 5, "", "ConnectException(refused)", true));
        String text = recording.end();
        assertTrue(text, text.startsWith("net=325 fail=323 slow=quark:208ms:200:dns3:conn81:h2,local6677:101ms:IOException(eos):conn15:http/1.1,"));
        assertTrue(text, text.endsWith(" localfail=323 ports=6677-6999"));
    }

    @Test
    public void oneFailedLoopbackPortIsNamedAlone() {
        NetTrace.Recording recording = NetTrace.begin();
        recording.add(new NetTrace.Entry("local", 9978, 30, -1, 12, "", "ConnectException(refused)", true));
        assertEquals("net=1 fail=1 slow=local9978:30ms:ConnectException(refused):conn12 localfail=1 ports=9978", recording.end());
    }
}
