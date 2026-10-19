package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;

/** Optional poster lookup cannot remove or replace the identity of an existing search hit. */
final class SearchPosterEnrichment {
    static Result apply(Result original, Callable<Result> lookup) {
        try {
            Result enriched = lookup.call();
            if (enriched == null) return original;
            var byId = new HashMap<String, Vod>();
            for (Vod item : enriched.getList()) {
                if (item != null && !item.getId().isEmpty() && !item.getName().isEmpty() && !item.getPic().isEmpty())
                    byId.put(item.getId(), item);
            }
            var retained = new ArrayList<>(original.getList());
            for (int i = 0; i < retained.size(); i++) {
                Vod item = retained.get(i);
                if (item == null) continue;
                Vod replacement = byId.get(item.getId());
                if (replacement != null) retained.set(i, replacement);
            }
            original.setList(retained);
        } catch (Exception ignored) {
            // A source's names/ids are already usable even when its optional detail endpoint fails.
        }
        return original;
    }
}
