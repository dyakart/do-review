package dev.doreview.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Parser/version and enclosing structural-chain fingerprint per logical row. */
public record ContextMap(String provider, List<String> fingerprints) {
    public ContextMap {
        Objects.requireNonNull(provider, "provider");
        fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
        if (provider.isBlank()) throw new IllegalArgumentException("Empty context provider");
    }

    public String fingerprint(int line) {
        return line >= 0 && line < fingerprints.size() ? fingerprints.get(line) : null;
    }

    /** Unsupported/broken parser: any file change changes the enclosing context. */
    public static ContextMap fallback(String text) {
        var snapshot = FileSnapshot.create(text);
        int rows = 1;
        for (int i = 0; i < snapshot.text().length(); i++) {
            if (snapshot.text().charAt(i) == '\n') rows++;
        }
        return new ContextMap("fallback:whole-file-v1", Collections.nCopies(rows, snapshot.id()));
    }
}
