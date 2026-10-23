package dev.doreview.core;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Read-only flattened view. All provenance semantics live in FileProjection.RowEvidence. */
final class EvidenceList extends AbstractList<EvidenceRef> {
    private final List<EvidenceRef> direct;
    private final List<FileProjection.PossibleEvidence> groups;
    private final int size;

    EvidenceList(List<EvidenceRef> direct, List<FileProjection.PossibleEvidence> groups) {
        this.direct = List.copyOf(direct);
        this.groups = List.copyOf(groups);
        int count = direct.size();
        for (var group : groups) count = Math.addExact(count, group.refs().size());
        size = count;
    }

    @Override public int size() { return size; }

    /** Traversal is linear in refs + groups rather than repeatedly scanning groups from the start. */
    @Override public Iterator<EvidenceRef> iterator() {
        return new Iterator<>() {
            private Iterator<EvidenceRef> cursor = direct.iterator();
            private int nextGroup;

            @Override public boolean hasNext() {
                while (!cursor.hasNext() && nextGroup < groups.size()) {
                    cursor = groups.get(nextGroup++).refs().iterator();
                }
                return cursor.hasNext();
            }

            @Override public EvidenceRef next() {
                if (!hasNext()) throw new NoSuchElementException();
                return cursor.next();
            }
        };
    }

    @Override public EvidenceRef get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        if (index < direct.size()) return direct.get(index);
        index -= direct.size();
        for (var group : groups) {
            if (index < group.refs().size()) return group.refs().get(index);
            index -= group.refs().size();
        }
        throw new IndexOutOfBoundsException(index);
    }
}
