package com.fongmi.android.tv.api;

import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.BaseSpiderGuard;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpiderSearchCapabilityTest {
    private static class NoPagination extends Spider {
        @Override public String searchContent(String key, boolean quick) { return "{\"list\":[]}"; }
    }

    private static class ImplementedPagination extends NoPagination {
        @Override public String searchContent(String key, boolean quick, String page) { return ""; }
    }

    private static class InheritedPagination extends ImplementedPagination {}

    private static class UnknownWrapper extends Spider {
        private final Spider delegate = new NoPagination();
        @Override public String searchContent(String key, boolean quick, String page) throws Exception {
            return delegate.searchContent(key, quick, page);
        }
    }

    private static class OverridingGuard extends BaseSpiderGuard {
        OverridingGuard() { super(new NoPagination()); }
        @Override public String searchContent(String key, boolean quick, String page) { return ""; }
    }

    private static class AmbiguousGuard extends BaseSpiderGuard {
        private final Spider another = new NoPagination();
        AmbiguousGuard() { super(new NoPagination()); }
    }

    private static class StaticFieldGuard extends BaseSpiderGuard {
        private static final Spider unrelated = new ImplementedPagination();
        StaticFieldGuard() { super(new NoPagination()); }
    }

    @Test public void baseAndTwoParameterOnlySpidersHaveNoPagination() {
        assertTrue(SpiderSearchCapability.hasDefaultPagination(new Spider() {}));
        assertTrue(SpiderSearchCapability.hasDefaultPagination(new NoPagination()));
    }

    @Test public void implementedOrInheritedPaginationBlankRemainsARealFailure() {
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new ImplementedPagination()));
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new InheritedPagination()));
    }

    @Test public void verifiedTransparentGuardFollowsItsRealDelegate() {
        assertTrue(SpiderSearchCapability.hasDefaultPagination(new BaseSpiderGuard(new NoPagination())));
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new BaseSpiderGuard(new ImplementedPagination())));
        assertTrue(SpiderSearchCapability.hasDefaultPagination(new BaseSpiderGuard(new BaseSpiderGuard(new NoPagination()))));
    }

    @Test public void unknownOrOverridingWrappersCannotProveMissingPagination() {
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new UnknownWrapper()));
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new OverridingGuard()));
    }

    @Test public void nullAmbiguousAndCyclicDelegatesStayUnknown() {
        assertFalse(SpiderSearchCapability.hasDefaultPagination(null));
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new BaseSpiderGuard(null)));
        assertFalse(SpiderSearchCapability.hasDefaultPagination(new AmbiguousGuard()));
        BaseSpiderGuard cycle = new BaseSpiderGuard(null);
        cycle.setDelegate(cycle);
        assertFalse(SpiderSearchCapability.hasDefaultPagination(cycle));
    }

    @Test public void unrelatedStaticFieldsDoNotMakeVerifiedForwardingAmbiguous() {
        assertTrue(SpiderSearchCapability.hasDefaultPagination(new StaticFieldGuard()));
    }

    @Test public void excessivelyDeepDelegationStaysUnknown() {
        Spider spider = new NoPagination();
        for (int i = 0; i < 9; i++) spider = new BaseSpiderGuard(spider);
        assertFalse(SpiderSearchCapability.hasDefaultPagination(spider));
    }
}
