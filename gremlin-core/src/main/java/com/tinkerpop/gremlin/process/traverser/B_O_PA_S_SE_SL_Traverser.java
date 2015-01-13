package com.tinkerpop.gremlin.process.traverser;


import com.tinkerpop.gremlin.process.Path;
import com.tinkerpop.gremlin.process.Step;
import com.tinkerpop.gremlin.process.TraversalSideEffects;
import com.tinkerpop.gremlin.process.Traverser;
import com.tinkerpop.gremlin.process.util.SparsePath;
import com.tinkerpop.gremlin.structure.Vertex;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class B_O_PA_S_SE_SL_Traverser<T> extends AbstractPathTraverser<T> {

    protected B_O_PA_S_SE_SL_Traverser() {
    }

    public B_O_PA_S_SE_SL_Traverser(final T t, final Step<T, ?> step) {
        super(t, step);
        this.path = getOrCreateFromCache(this.sideEffects).extend(step.getLabel(), t);
    }

    @Override
    public int hashCode() {
        return this.t.hashCode() + this.future.hashCode() + this.loops;
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof B_O_PA_S_SE_SL_Traverser
                && ((B_O_PA_S_SE_SL_Traverser) object).get().equals(this.t)
                && ((B_O_PA_S_SE_SL_Traverser) object).getFuture().equals(this.getFuture())
                && ((B_O_PA_S_SE_SL_Traverser) object).loops() == this.loops()
                && (null == this.sack);
    }

    @Override
    public Traverser.Admin<T> attach(final Vertex vertex) {
        super.attach(vertex);
        final Path newSparsePath = getOrCreateFromCache(this.sideEffects);
        this.path.forEach((k, v) -> newSparsePath.extend(k, v));
        this.path = newSparsePath;
        return this;
    }

    //////////////////////

    private static final Map<TraversalSideEffects, SparsePath> PATH_CACHE = new WeakHashMap<>();

    private static SparsePath getOrCreateFromCache(final TraversalSideEffects sideEffects) {
        SparsePath path = PATH_CACHE.get(sideEffects);
        if (null == path) {
            path = SparsePath.make();
            PATH_CACHE.put(sideEffects, path);
        }
        return path;
    }

}