package com.github.catvod.net;

import static org.junit.Assert.assertEquals;

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
    public void onlyShortChinesePluginMessagesAreKept() {
        assertEquals("IOException(分享已失效)", NetTrace.reason(new IOException("分享已失效")));
        assertEquals("IOException(容量不足_请清理网盘)", NetTrace.reason(new IOException("容量不足 请清理网盘")));
        assertEquals("other", NetTrace.readable("文件《希望》不存在"));
        assertEquals("other", NetTrace.readable("失效 https://pan.quark.cn/s/abc"));
        assertEquals("other", NetTrace.readable("令牌 abcdef123456 已过期"));
        assertEquals("other", NetTrace.readable("account expired"));
    }

    @Test
    public void emptyRecordingSummarizesNothing() {
        assertEquals("net=0 fail=0", NetTrace.begin().end());
    }
}
