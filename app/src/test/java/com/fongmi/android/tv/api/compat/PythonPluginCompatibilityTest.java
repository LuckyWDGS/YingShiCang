package com.fongmi.android.tv.api.compat;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.AiDj;
import com.github.catvod.spider.BaseSpiderGuard;
import com.github.catvod.spider.merge.oE;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PythonPluginCompatibilityTest {
    @Test public void auditedProtocolAndInheritedProtocolRequireRuntime() {
        assertTrue(PythonPluginCompatibility.requiresRuntime(new AiDj()));
        assertTrue(PythonPluginCompatibility.requiresRuntime(new oE()));
        assertTrue(PythonPluginCompatibility.requiresRuntime(new AiDj() {}));
    }

    @Test public void verifiedGuardsRetainDelegateProtocol() {
        assertTrue(PythonPluginCompatibility.requiresRuntime(new BaseSpiderGuard(new AiDj())));
        assertTrue(PythonPluginCompatibility.requiresRuntime(new BaseSpiderGuard(new BaseSpiderGuard(new AiDj()))));
        assertFalse(PythonPluginCompatibility.requiresRuntime(new BaseSpiderGuard(new Spider() {})));
    }

    @Test public void brandingAndUnprovenWrappersDoNotStartPython() {
        class LooksLikeAiDj extends Spider { private final Spider child = new AiDj(); }
        assertFalse(PythonPluginCompatibility.requiresRuntime(new LooksLikeAiDj()));
        assertFalse(PythonPluginCompatibility.requiresRuntime(new Spider() {}));
        assertFalse(PythonPluginCompatibility.requiresRuntime(null));
        assertFalse(PythonPluginCompatibility.requiresRuntime(new BaseSpiderGuard(null)));
    }

    @Test public void ambiguousGuardsAndOverlongChainsRemainUnchanged() {
        class AmbiguousGuard extends BaseSpiderGuard {
            private final Spider another = new AiDj();
            AmbiguousGuard() { super(new AiDj()); }
        }
        assertFalse(PythonPluginCompatibility.requiresRuntime(new AmbiguousGuard()));
        Spider spider = new AiDj();
        for (int i = 0; i < 8; i++) spider = new BaseSpiderGuard(spider);
        assertFalse(PythonPluginCompatibility.requiresRuntime(spider));
    }

    @Test public void unverifiedInitOverridesKeepOriginalHostContext() {
        class OverridingGuard extends BaseSpiderGuard {
            OverridingGuard() { super(new AiDj()); }
            @Override public void init(android.content.Context context, String extend) {}
        }
        assertFalse(PythonPluginCompatibility.requiresRuntime(new OverridingGuard()));
        assertFalse(PythonPluginCompatibility.requiresRuntime(new AiDj() {
            @Override public void init(android.content.Context context, String extend) {}
        }));
    }
}
