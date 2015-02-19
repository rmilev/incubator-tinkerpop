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
package org.apache.tinkerpop.gremlin.process.graph.traversal.step.sideEffect;

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.process.Traversal;
import org.apache.tinkerpop.gremlin.process.TraversalEngine;
import org.apache.tinkerpop.gremlin.process.Traverser;
import org.apache.tinkerpop.gremlin.process.computer.KeyValue;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.VertexTraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.graph.traversal.step.SideEffectCapable;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalMatrix;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.EngineDependent;
import org.apache.tinkerpop.gremlin.process.traversal.step.MapReducer;
import org.apache.tinkerpop.gremlin.process.traversal.step.Reversible;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;
import org.apache.tinkerpop.gremlin.process.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.util.BulkSet;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.function.HashMapSupplier;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class GroupSideEffectStep<S, K, V, R> extends SideEffectStep<S> implements SideEffectCapable, TraversalParent, Reversible, EngineDependent, MapReducer<K, Collection<V>, K, R, Map<K, R>> {

    private char state = 'k';
    private Traversal.Admin<S, K> keyTraversal = new IdentityTraversal<>();
    private Traversal.Admin<S, V> valueTraversal = new IdentityTraversal<>();
    private Traversal.Admin<Collection<V>, R> reduceTraversal = null;
    private String sideEffectKey;
    private boolean onGraphComputer = false;
    private Map<K, Collection<V>> tempGroupByMap;

    public GroupSideEffectStep(final Traversal.Admin traversal, final String sideEffectKey) {
        super(traversal);
        this.sideEffectKey = sideEffectKey;
        this.traversal.asAdmin().getSideEffects().registerSupplierIfAbsent(this.sideEffectKey, HashMapSupplier.instance());
    }

    @Override
    protected void sideEffect(final Traverser.Admin<S> traverser) {
        final Map<K, Collection<V>> groupByMap = null == this.tempGroupByMap ? traverser.sideEffects(this.sideEffectKey) : this.tempGroupByMap; // for nested traversals and not !starts.hasNext()
        doGroup(traverser.asAdmin(), groupByMap, this.keyTraversal, this.valueTraversal);
        if (!this.onGraphComputer && null != this.reduceTraversal && !this.starts.hasNext()) {
            this.tempGroupByMap = groupByMap;
            final Map<K, R> reduceMap = new HashMap<>();
            doReduce(groupByMap, reduceMap, this.reduceTraversal);
            traverser.sideEffects(this.sideEffectKey, reduceMap);
        }
    }

    @Override
    public String getSideEffectKey() {
        return this.sideEffectKey;
    }

    private static <S, K, V> void doGroup(final Traverser.Admin<S> traverser, final Map<K, Collection<V>> groupMap, final Traversal.Admin<S, K> keyTraversal, final Traversal.Admin<S, V> valueTraversal) {
        final K key = TraversalUtil.apply(traverser, keyTraversal);
        final V value = TraversalUtil.apply(traverser, valueTraversal);
        Collection<V> values = groupMap.get(key);
        if (null == values) {
            values = new BulkSet<>();
            groupMap.put(key, values);
        }
        TraversalHelper.addToCollectionUnrollIterator(values, value, traverser.bulk());
    }

    private static <K, V, R> void doReduce(final Map<K, Collection<V>> groupMap, final Map<K, R> reduceMap, final Traversal.Admin<Collection<V>, R> reduceTraversal) {
        groupMap.forEach((k, vv) -> reduceMap.put(k, TraversalUtil.apply(vv, reduceTraversal)));
    }

    @Override
    public void onEngine(final TraversalEngine traversalEngine) {
        this.onGraphComputer = traversalEngine.isComputer();
    }

    @Override
    public MapReduce<K, Collection<V>, K, R, Map<K, R>> getMapReduce() {
        return new GroupSideEffectMapReduce<>(this);
    }

    @Override
    public String toString() {
        return TraversalHelper.makeStepString(this, this.sideEffectKey, this.keyTraversal, this.valueTraversal, this.reduceTraversal);
    }

    @Override
    public <A, B> List<Traversal.Admin<A, B>> getLocalChildren() {
        return null == this.reduceTraversal ? (List) Arrays.asList(this.keyTraversal, this.valueTraversal) : (List) Arrays.asList(this.keyTraversal, this.valueTraversal, this.reduceTraversal);
    }

    public Traversal.Admin<Collection<V>, R> getReduceTraversal() {
        return this.reduceTraversal;
    }

    @Override
    public void addLocalChild(final Traversal.Admin<?, ?> kvrTraversal) {
        if ('k' == this.state) {
            this.keyTraversal = this.integrateChild(kvrTraversal);
            this.state = 'v';
        } else if ('v' == this.state) {
            this.valueTraversal = this.integrateChild(kvrTraversal);
            this.state = 'r';
        } else if ('r' == this.state) {
            this.reduceTraversal = this.integrateChild(kvrTraversal);
            this.state = 'x';
        } else {
            throw new IllegalStateException("The key, value, and reduce functions for group()-step have already been set");
        }
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return this.getSelfAndChildRequirements(TraverserRequirement.SIDE_EFFECTS, TraverserRequirement.BULK);
    }

    @Override
    public GroupSideEffectStep<S, K, V, R> clone() throws CloneNotSupportedException {
        final GroupSideEffectStep<S, K, V, R> clone = (GroupSideEffectStep<S, K, V, R>) super.clone();
        clone.keyTraversal = clone.integrateChild(this.keyTraversal.clone());
        clone.valueTraversal = clone.integrateChild(this.valueTraversal.clone());
        if (null != this.reduceTraversal)
            clone.reduceTraversal = clone.integrateChild(this.reduceTraversal.clone());
        return clone;
    }

    ///////////

    public static final class GroupSideEffectMapReduce<K, V, R> implements MapReduce<K, Collection<V>, K, R, Map<K, R>> {

        public static final String GROUP_BY_STEP_SIDE_EFFECT_KEY = "gremlin.groupStep.sideEffectKey";
        public static final String GROUP_BY_STEP_STEP_ID = "gremlin.groupStep.stepId";

        private String sideEffectKey;
        private String groupStepId;
        private Traversal.Admin<Collection<V>, R> reduceTraversal;
        private Supplier<Map<K, R>> mapSupplier;

        private GroupSideEffectMapReduce() {

        }

        public GroupSideEffectMapReduce(final GroupSideEffectStep step) {
            this.groupStepId = step.getId();
            this.sideEffectKey = step.getSideEffectKey();
            this.reduceTraversal = step.getReduceTraversal();
            this.mapSupplier = step.getTraversal().asAdmin().getSideEffects().<Map<K, R>>getRegisteredSupplier(this.sideEffectKey).orElse(HashMap::new);
        }

        @Override
        public void storeState(final Configuration configuration) {
            MapReduce.super.storeState(configuration);
            configuration.setProperty(GROUP_BY_STEP_SIDE_EFFECT_KEY, this.sideEffectKey);
            configuration.setProperty(GROUP_BY_STEP_STEP_ID, this.groupStepId);
        }

        @Override
        public void loadState(final Configuration configuration) {
            this.sideEffectKey = configuration.getString(GROUP_BY_STEP_SIDE_EFFECT_KEY);
            this.groupStepId = configuration.getString(GROUP_BY_STEP_STEP_ID);
            final Traversal.Admin<?, ?> traversal = TraversalVertexProgram.getTraversalSupplier(configuration).get();
            if (!traversal.isLocked())
                traversal.applyStrategies(); // TODO: this is a scary error prone requirement, but only a problem for GroupStep
            final GroupSideEffectStep groupSideEffectStep = new TraversalMatrix<>(traversal).getStepById(this.groupStepId);
            this.reduceTraversal = groupSideEffectStep.getReduceTraversal();
            this.mapSupplier = traversal.getSideEffects().<Map<K, R>>getRegisteredSupplier(this.sideEffectKey).orElse(HashMap::new);
        }

        @Override
        public boolean doStage(final Stage stage) {
            return !stage.equals(Stage.COMBINE);
        }

        @Override
        public void map(final Vertex vertex, final MapEmitter<K, Collection<V>> emitter) {
            VertexTraversalSideEffects.of(vertex).<Map<K, Collection<V>>>orElse(this.sideEffectKey, Collections.emptyMap()).forEach(emitter::emit);
        }

        @Override
        public void reduce(final K key, final Iterator<Collection<V>> values, final ReduceEmitter<K, R> emitter) {
            final Set<V> set = new BulkSet<>();
            values.forEachRemaining(set::addAll);
            emitter.emit(key, (null == this.reduceTraversal) ? (R) set : TraversalUtil.apply(set, this.reduceTraversal));
        }

        @Override
        public Map<K, R> generateFinalResult(final Iterator<KeyValue<K, R>> keyValues) {
            final Map<K, R> map = this.mapSupplier.get();
            keyValues.forEachRemaining(keyValue -> map.put(keyValue.getKey(), keyValue.getValue()));
            return map;
        }

        @Override
        public String getMemoryKey() {
            return this.sideEffectKey;
        }

        @Override
        public GroupSideEffectMapReduce<K, V, R> clone() throws CloneNotSupportedException {
            final GroupSideEffectMapReduce<K, V, R> clone = (GroupSideEffectMapReduce<K, V, R>) super.clone();
            if (null != clone.reduceTraversal)
                clone.reduceTraversal = this.reduceTraversal.clone();
            return clone;
        }

        @Override
        public String toString() {
            return StringFactory.mapReduceString(this, this.getMemoryKey());
        }
    }
}