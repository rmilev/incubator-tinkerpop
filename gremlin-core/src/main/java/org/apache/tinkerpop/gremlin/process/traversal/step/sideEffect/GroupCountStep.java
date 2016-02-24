/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect;

import org.apache.tinkerpop.gremlin.process.computer.MemoryComputeKey;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.ByModulating;
import org.apache.tinkerpop.gremlin.process.traversal.step.GraphComputing;
import org.apache.tinkerpop.gremlin.process.traversal.step.SideEffectCapable;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.MapHelper;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.function.HashMapSupplier;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class GroupCountStep<S, E> extends SideEffectStep<S> implements SideEffectCapable, GraphComputing, TraversalParent, ByModulating {

    private Traversal.Admin<S, E> keyTraversal = null;
    private String sideEffectKey;

    public GroupCountStep(final Traversal.Admin traversal, final String sideEffectKey) {
        super(traversal);
        this.sideEffectKey = sideEffectKey;
        this.traversal.asAdmin().getSideEffects().registerSupplierIfAbsent(this.sideEffectKey, HashMapSupplier.instance());
    }

    @Override
    protected void sideEffect(final Traverser.Admin<S> traverser) {
        final Map<Object, Long> groupCountMap = traverser.sideEffects(this.sideEffectKey);
        MapHelper.incr(groupCountMap, TraversalUtil.applyNullable(traverser.asAdmin(), this.keyTraversal), traverser.bulk());
    }

    @Override
    public String getSideEffectKey() {
        return this.sideEffectKey;
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.sideEffectKey, this.keyTraversal);
    }

    @Override
    public void addLocalChild(final Traversal.Admin<?, ?> groupTraversal) {
        this.keyTraversal = this.integrateChild(groupTraversal);
    }

    @Override
    public List<Traversal.Admin<S, E>> getLocalChildren() {
        return null == this.keyTraversal ? Collections.emptyList() : Collections.singletonList(this.keyTraversal);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return this.getSelfAndChildRequirements(TraverserRequirement.BULK, TraverserRequirement.SIDE_EFFECTS);
    }

    @Override
    public GroupCountStep<S, E> clone() {
        final GroupCountStep<S, E> clone = (GroupCountStep<S, E>) super.clone();
        if (null != this.keyTraversal)
            clone.keyTraversal = clone.integrateChild(this.keyTraversal.clone());
        return clone;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode() ^ this.sideEffectKey.hashCode();
        if (this.keyTraversal != null) result ^= this.keyTraversal.hashCode();
        return result;
    }

    @Override
    public void modulateBy(final Traversal.Admin<?, ?> keyTraversal) throws UnsupportedOperationException {
        this.keyTraversal = this.integrateChild(keyTraversal);
    }

    @Override
    public void onGraphComputer() {

    }

    @Override
    public Object generateFinalResult(final Object map) {
        return map;
    }

    @Override
    public Optional<MemoryComputeKey> getMemoryComputeKey() {
        return Optional.of(MemoryComputeKey.of(this.getSideEffectKey(), GroupCountBiOperator.INSTANCE, false, false));
    }

    ///////

    public static final class GroupCountBiOperator<E> implements BinaryOperator<Map<E, Long>>, Serializable {

        private static GroupCountBiOperator INSTANCE = new GroupCountBiOperator();

        @Override
        public Map<E, Long> apply(final Map<E, Long> mutatingSeed, final Map<E, Long> map) {
            map.forEach((k, v) -> MapHelper.incr(mutatingSeed, k, v));
            return mutatingSeed;
        }
    }
}