/* ==========================================
 * JGraphT : a free Java graph-theory library
 * ==========================================
 *
 * Project Info:  http://jgrapht.sourceforge.net/
 * Project Creator:  Barak Naveh (http://sourceforge.net/users/barak_naveh)
 *
 * (C) Copyright 2003-2008, by Barak Naveh and Contributors.
 *
 * This program and the accompanying materials are dual-licensed under
 * either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation, or (at your option) any
 * later version.
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation.
 */
/* ----------------------
 * AbstractBaseGraph.java
 * ----------------------
 * (C) Copyright 2003-2008, by Barak Naveh and Contributors.
 *
 * Original Author:  Barak Naveh
 * Contributor(s):   John V. Sichi
 *                   Christian Hammer
 *
 * $Id$
 *
 * Changes
 * -------
 * 24-Jul-2003 : Initial revision (BN);
 * 10-Aug-2003 : General edge refactoring (BN);
 * 06-Nov-2003 : Change edge sharing semantics (JVS);
 * 07-Feb-2004 : Enabled serialization (BN);
 * 11-Mar-2004 : Made generic (CH);
 * 01-Jun-2005 : Added EdgeListFactory (JVS);
 * 07-May-2006 : Changed from List<Edge> to Set<Edge> (JVS);
 * 28-May-2006 : Moved connectivity info from edge to graph (JVS);
 *
 */
package edu.kit.joana.util.graph;

import edu.kit.joana.util.collections.Disowning;
import edu.kit.joana.util.collections.ModifiableArraySet;
import edu.kit.joana.util.collections.SimpleVectorBase;
import org.jgrapht.*;
import org.jgrapht.graph.AbstractGraph;
import org.jgrapht.util.ArrayUnenforcedSet;
import org.jgrapht.util.TypeUtil;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;



/**
 * The most general implementation of the {@link org.jgrapht.Graph} interface.
 * Its subclasses add various restrictions to get more specific graphs. The
 * decision whether it is directed or undirected is decided at construction time
 * and cannot be later modified (see constructor for details).
 *
 * <p>This graph implementation guarantees deterministic vertex and edge set
 * ordering (via {@link LinkedHashMap} and {@link LinkedHashSet}).</p>
 *
 * @author Barak Naveh
 * @since Jul 24, 2003
 */
public abstract class AbstractBaseGraph<V extends IntegerIdentifiable, E extends KnowsVertices<V>>
    extends AbstractGraph<V, E>
    implements
        EfficientGraph<V, E>,
        DirectedGraph<V, E>,
        Cloneable,
        Serializable
{
    private static final long serialVersionUID = -1263088497616142427L;

    private EdgeFactory<V, E> edgeFactory;
    private transient Set<E> unmodifiableEdgeSet = null;
    private transient Set<V> unmodifiableVertexSet = null;
    private Map<V, DirectedEdgeContainer<E,E[]>> vertexMap;
    private final Supplier<Map<V,DirectedEdgeContainer<E,E[]>>> vertexMapConstructor;
    private final Class<E> classE;
    private final Function<E[], Disowning<E>> arraySetProvider;

	private boolean changed = true;
	private int hashCode;



    /**
     * Construct a new graph. The graph can either be directed or undirected,
     * depending on the specified edge factory.
     *
     * @param ef the edge factory of the new graph.
     * @param allowMultipleEdges whether to allow multiple edges or not.
     * @param allowLoops whether to allow edges that are self-loops or not.
     *
     * @throws NullPointerException if the specified edge factory is <code>
     * null</code>.
     */
    protected <T> AbstractBaseGraph(
        EdgeFactory<V, E> ef,
        Supplier<Map<V,DirectedEdgeContainer<E,E[]>>> vertexMapConst,
        Function<E[], Disowning<E>> asProvider,
        Class<E> clazzE
        )
    {
        if (ef == null) {
            throw new NullPointerException();
        }

        edgeFactory = ef;
        vertexMapConstructor = vertexMapConst;
        classE  = clazzE;

        vertexMap = vertexMapConstructor.get();
        this.arraySetProvider = asProvider;
    }

    protected <T> AbstractBaseGraph(
            EdgeFactory<V, E> ef,
            Supplier<Map<V,DirectedEdgeContainer<E,E[]>>> vertexMapConst,
            Class<E> clazzE
            ) {
    	this(ef, vertexMapConst, (E[] es) ->  ModifiableArraySet.own(es, clazzE), clazzE);
    }

    /**
     * @see Graph#getEdgeFactory()
     */
    @Override public EdgeFactory<V, E> getEdgeFactory()
    {
        return edgeFactory;
    }

		/**
		 * @see Graph#addEdge(Object, Object)
		 */
		public E addEdgeAndVertices(V sourceVertex, V targetVertex)
		{
			addVertex(sourceVertex);
			addVertex(targetVertex);
			return addEdge(sourceVertex, targetVertex);
		}

	public E addEdgeAndVertices(V sourceVertex, V targetVertex, BiFunction<V, V, E> factory)
	{
		addVertex(sourceVertex);
		addVertex(targetVertex);
		E edge = factory.apply(sourceVertex, targetVertex);
		addEdge(sourceVertex, targetVertex, edge);
		return edge;
	}

    /**
     * @see Graph#addEdge(Object, Object)
     */
    @Override public E addEdge(V sourceVertex, V targetVertex)
    {
        assertVertexExist(sourceVertex);
        assertVertexExist(targetVertex);

        E e = edgeFactory.createEdge(sourceVertex, targetVertex);

        if (containsEdge(e)) { // this restriction should stay!

            return null;
        } else {
            addEdgeToTouchingVertices(e);
            changed = true;

            return e;
        }
    }

    /**
     * @see Graph#addEdge(Object, Object, Object)
     */
    @Override public boolean addEdge(V sourceVertex, V targetVertex, E e)
    {
        if (e == null) {
            throw new NullPointerException();
        } else if (containsEdge(e)) {
            return false;
        }

        if (sourceVertex != null && !(containsVertex(sourceVertex))) {
        	containsVertex(sourceVertex);
        	throw new IllegalStateException();
        }
        assertVertexExist(sourceVertex);
        assertVertexExist(targetVertex);

        addEdgeToTouchingVertices(e);
        changed = true;

        return true;
    }

    /**
     * @see Graph#addEdge(Object, Object, Object),
     * except some failure cases may go unnoticed unless running with java assertions enabled
     */
    public boolean addEdgeUnsafe(V sourceVertex, V targetVertex, E e)
    {
    	assert e != null;

        assert assertVertexExist(sourceVertex);
        assert assertVertexExist(targetVertex);

        assert sourceVertex == e.getSource();
        assert targetVertex == e.getTarget();

        final boolean addedInTarget = vertexMap.get(targetVertex).addIncomingEdge(arraySetProvider, e);
        if (addedInTarget) {
            changed = true;
            final boolean addedInSource = vertexMap.get(sourceVertex).addOutgoingEdge(arraySetProvider, e);
            assert addedInSource;
        } else {
            assert !vertexMap.get(sourceVertex).addOutgoingEdge(arraySetProvider, e);
        }

        return addedInTarget;
    }

    @Override
    public void addIncomingEdgesAtUNSAFE(V targetVertex, Set<E> edges) {
    	assert assertVertexExist(targetVertex);

    	vertexMap.get(targetVertex).addIncomingEdges(arraySetProvider, edges);
    	changed = true;
    }

    @Override
    public void addOutgoingEdgesAtUNSAFE(V sourceVertex, Set<E> edges) {
    	assert assertVertexExist(sourceVertex);

    	vertexMap.get(sourceVertex).addOutgoingEdges(arraySetProvider, edges);
    	changed = true;
    }

    /**
     * @see Graph#addVertex(Object)
     */
    @Override public boolean addVertex(V v)
    {
        if (v == null) {
            throw new NullPointerException();
        } else if (containsVertex(v)) {
            return false;
        } else {
            vertexMap.put(v, new ArraySetDirectedEdgeContainer<V, E>(classE));
            changed = true;

            return true;
        }
    }

    /**
     * @see Graph#addVertex(Object),
     * except some failure cases may go unnoticed
     */
    @Override
    public void addVertexUnsafe(V v) {
        if (v == null) {
            throw new NullPointerException();
        } else {
            vertexMap.put(v, new ArraySetDirectedEdgeContainer<V, E>(classE));
            changed = true;
        }
    }

    /**
     * @see Graph#getEdgeSource(Object)
     */
    @Override public V getEdgeSource(E e)
    {
        return e.getSource();
    }

    /**
     * @see Graph#getEdgeTarget(Object)
     */
    @Override public V getEdgeTarget(E e)
    {
        return e.getTarget();
    }

    /**
     * Returns a shallow copy of this graph instance. Neither edges nor vertices
     * are cloned.
     *
     * @return a shallow copy of this set.
     *
     * @throws RuntimeException
     *
     * @see java.lang.Object#clone()
     */
    @Override public Object clone()
    {
        try {
            TypeUtil<AbstractBaseGraph<V, E>> typeDecl = null;

            AbstractBaseGraph<V, E> newGraph =
                TypeUtil.uncheckedCast(super.clone(), typeDecl);

            newGraph.edgeFactory = this.edgeFactory;
            newGraph.unmodifiableEdgeSet = null;
            newGraph.unmodifiableVertexSet = null;

            // NOTE:  it's important for this to happen in an object
            // method so that the new inner class instance gets associated with
            // the right outer class instance
            newGraph.vertexMap = vertexMapConstructor.get();

            org.jgrapht.Graphs.addGraph(newGraph, this);
            newGraph.changed = true;

            return newGraph;
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public boolean containsEdge(V sourceVertex, V targetVertex, Predicate<E> predicate) {
    	final Set<E> outgoing = outgoingEdgesOf(sourceVertex);
    	for (E e : outgoing) {
    		if (e.getTarget().equals(targetVertex) && predicate.test(e)) return true;
    	}
    	return false;
    }

    /**
     * @see Graph#containsVertex(Object)
     */
    @Override public boolean containsVertex(V v)
    {
        return vertexMap.containsKey(v);
    }

    /**
     * @see Graph#edgeSet()
     */
    @Override public Set<E> edgeSet()
    {
        if (unmodifiableEdgeSet == null) {
            unmodifiableEdgeSet = new EdgeSetView(vertexMap);
        }

        return unmodifiableEdgeSet;
    }

    private class EdgeSetView implements Set<E> {
    	final Map<V, DirectedEdgeContainer<E, E[]>> vertexMap;
    	EdgeSetView(Map<V, DirectedEdgeContainer<E, E[]>> vertexMap) {
    		this.vertexMap = vertexMap;
    	}

		/* (non-Javadoc)
		 * @see java.util.Set#add(java.lang.Object)
		 */
		@Override
		public boolean add(E e) {
			throw new UnsupportedOperationException();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#addAll(java.util.Collection)
		 */
		@Override
		public boolean addAll(Collection<? extends E> c) {
			throw new UnsupportedOperationException();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#clear()
		 */
		@Override
		public void clear() {
			throw new UnsupportedOperationException();

		}

		/* (non-Javadoc)
		 * @see java.util.Set#contains(java.lang.Object)
		 */
		@Override
		public boolean contains(Object o) {
			if (!(o instanceof KnowsVertices)) {
				return false;
			}
			DirectedEdgeContainer<E, E[]> vc
					= vertexMap.get(((KnowsVertices<?>) o).getSource());

			if (vc == null || vc.outgoing() == null) {
				assert !containsByTarget(o);
				return false;
			}
			final Set<E> outgoing = arraySetProvider.apply(vc.outgoing());

			final boolean result = outgoing.contains(o);
			assert result == containsByTarget(o);

			return result;
		}

		private boolean containsByTarget(Object o) {
			if (!(o instanceof KnowsVertices)) {
				return false;
			}
			DirectedEdgeContainer<E, E[]> vc
					= vertexMap.get(((KnowsVertices<?>) o).getTarget());

			if (vc == null || vc.outgoing() == null) {
				return false;
			}
			final Set<E> incoming = arraySetProvider.apply(vc.incoming());
			return incoming.contains(o);
		}

		/* (non-Javadoc)
		 * @see java.util.Set#containsAll(java.util.Collection)
		 */
		@Override
		public boolean containsAll(Collection<?> c) {
			for (Object o : c) {
				if (!contains(o)) {
					return false;
				}
			}
			return true;
		}

		/* (non-Javadoc)
		 * @see java.util.Set#isEmpty()
		 */
		@Override
		public boolean isEmpty() {
			return size() == 0;
		}

		/* (non-Javadoc)
		 * @see java.util.Set#iterator()
		 */
		@Override
		public Iterator<E> iterator() {
			return new EdgeSetViewIterator() ;
		}

		/* (non-Javadoc)
		 * @see java.util.Set#remove(java.lang.Object)
		 */
		@Override
		public boolean remove(Object o) {
			throw new UnsupportedOperationException();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#removeAll(java.util.Collection)
		 */
		@Override
		public boolean removeAll(Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#retainAll(java.util.Collection)
		 */
		@Override
		public boolean retainAll(Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#size()
		 */
		@Override
		public int size() {
			// since the "outgoing" is initialized lazily, c may be null for a vertex w/o outgoing edges/
			return vertexMap.values().stream().mapToInt(c-> (c == null) ? 0 : arraySetProvider.apply(c.outgoing()).size()).sum();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#toArray()
		 */
		@Override
		public Object[] toArray() {
			return toList().toArray();
		}

		/* (non-Javadoc)
		 * @see java.util.Set#toArray(java.lang.Object[])
		 */
		@Override
		public <T> T[] toArray(T[] a) {
			return toList().toArray(a);
		}

		private List<E> toList() {
			List<E> tmp = new ArrayList<>();
			for (E e : this) {
				tmp.add(e);
			}
			return tmp;
		}

		/* (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			String comma = "";
			StringBuilder sb = new StringBuilder();
			sb.append("[");
			for (E e : this) {
				sb.append(comma);
				sb.append(e);
				comma = ",";
			}
			sb.append("]");
			return sb.toString();
		}

		private class EdgeSetViewIterator implements Iterator<E>{
			Iterator<DirectedEdgeContainer<E, E[]>> ecIt;
			Iterator<E> edgeIt;
			E next;

			EdgeSetViewIterator() {
				ecIt = vertexMap.values().iterator();
				advance();
			}

			@Override
			public boolean hasNext() {
				return next != null;
			}

			@Override
			public E next() {
				E ret = next;
				if (edgeIt.hasNext()) {
					next = edgeIt.next();
				} else {
					advance();
				}
				return ret;
			}

			private void advance() {
				DirectedEdgeContainer<E, E[]> vc = null;
				while (ecIt.hasNext()) {
					vc = ecIt.next();
					Set<E> outgoing = arraySetProvider.apply(vc.outgoing());
					if (vc != null && !outgoing.isEmpty()) {
						edgeIt = outgoing.iterator();
						next = edgeIt.next();
						return;
					}
				}
				next = null;
			}
		}
    }

    /**
     * @see Graph#removeEdge(Object, Object)
     */
    @Override public E removeEdge(V sourceVertex, V targetVertex)
    {
        E e = getEdge(sourceVertex, targetVertex);

        if (e != null) {
            removeEdgeFromTouchingVertices(e);
            changed = true;
        }

        return e;
    }

    /**
     * @see Graph#removeEdge(Object)
     */
    @Override public boolean removeEdge(E e)
    {
        if (containsEdge(e)) {
            removeEdgeFromTouchingVertices(e);
            changed = true;
            return true;
        } else {
            return false;
        }
    }

    /**
     * @see Graph#removeEdge(Object)
     */
    public boolean removeEdgeUnsafe(E e)
    {
        return removeEdgeFromTouchingVertices(e);
    }

    /**
     * @see Graph#removeVertex(Object)
     */
    @Override public boolean removeVertex(V v)
    {
        if (containsVertex(v)) {
            Set<E> touchingEdgesList = edgesOf(v);

            // cannot iterate over list - will cause
            // ConcurrentModificationException
            removeAllEdges(new ArrayList<E>(touchingEdgesList));

            vertexMap.remove(v); // remove the vertex itself
            changed = true;

            return true;
        } else {
            return false;
        }
    }

    /**
     * @see Graph#vertexSet()
     */
    @Override public Set<V> vertexSet()
    {
        if (unmodifiableVertexSet == null) {
            unmodifiableVertexSet =
                Collections.unmodifiableSet(vertexMap.keySet());
        }

        return unmodifiableVertexSet;
    }


    /**
     * @see Graph#getEdgeWeight(Object)
     */
    @Override public double getEdgeWeight(E e)
    {
    	if (e == null) {
            throw new NullPointerException();
        } else {
            return WeightedGraph.DEFAULT_EDGE_WEIGHT;
        }
    }


    public interface DirectedEdgeContainer<EE, Rep> {
        Set<EE> getUnmodifiableIncomingEdges(Function<EE[], Disowning<EE>> asProvider);
        Set<EE> getUnmodifiableOutgoingEdges(Function<EE[], Disowning<EE>> asProvider);
        boolean addIncomingEdge(Function<EE[], Disowning<EE>> asProvider, EE e);
        void    addIncomingEdges(Function<EE[], Disowning<EE>> asProvider, Set<EE> edges);
        void    addOutgoingEdges(Function<EE[], Disowning<EE>> asProvider, Set<EE> edges);
        boolean addOutgoingEdge(Function<EE[], Disowning<EE>> asProvider, EE e);
        boolean removeIncomingEdge(Function<EE[], Disowning<EE>> asProvider, EE e);
        boolean removeOutgoingEdge(Function<EE[], Disowning<EE>> asProvider, EE e);
        void removeIncomingEdges(Function<EE[], Disowning<EE>> asProvider);
        void removeOutgoingEdges(Function<EE[], Disowning<EE>> asProvider);
        Rep incoming();
        Rep outgoing();

    }

    /**
     * A container for vertex edges.
     *
     * <p>In this edge container we use arrays to minimize memory toll.
     *
     * @author Martin Hecker
     */
    protected static final class ArraySetDirectedEdgeContainer<V, EE extends KnowsVertices<V>> implements DirectedEdgeContainer<EE, EE[]>
    {
        EE[] incoming;
        EE[] outgoing;

        @SuppressWarnings("unchecked")
		ArraySetDirectedEdgeContainer(Class<EE> clazz)
        {
        	incoming = (EE[]) Array.newInstance(clazz, 0);
        	outgoing = (EE[]) Array.newInstance(clazz, 0);
        }

        @Override
        public EE[] incoming() {
        	return incoming;
        }

        @Override
        public EE[] outgoing() {
        	return outgoing;
        }


        public final Set<EE> getUnmodifiableIncomingEdges(Function<EE[], Disowning<EE>> asProvider)
        {
        	assert incoming != null;
            return asProvider.apply(incoming);
        }

        public final Set<EE> getUnmodifiableOutgoingEdges(Function<EE[], Disowning<EE>> asProvider)
        {
        	assert outgoing != null;
            return asProvider.apply(outgoing);
        }


        @Override
		public final boolean addIncomingEdge(Function<EE[], Disowning<EE>> asProvider, EE e)
        {
        	final Disowning<EE> set;
        	final boolean added;

        	assert incoming != null;

           	set = asProvider.apply(incoming);
           	added = set.add(e);
            incoming = set.disown();

            return added;
        }

		@Override
		public final boolean addOutgoingEdge(Function<EE[], Disowning<EE>> asProvider, EE e)
        {
        	assert outgoing != null;

        	final Disowning<EE> set = asProvider.apply(outgoing);
        	final boolean added = set.add(e);
           	outgoing = set.disown();

            return added;
        }

		@Override
		public void addIncomingEdges(Function<EE[], Disowning<EE>> asProvider, Set<EE> edges) {
			assert incoming != null;
			final Disowning<EE> set = asProvider.apply(incoming);
			set.addAll(edges);
			incoming = set.disown();
		}

		@Override
		public void addOutgoingEdges(Function<EE[], Disowning<EE>> asProvider, Set<EE> edges) {
			assert outgoing != null;
			final Disowning<EE> set = asProvider.apply(outgoing);
			set.addAll(edges);
			outgoing = set.disown();
		}

        /**
         * .
         *
         * @param e
         */
        public final boolean removeIncomingEdge(Function<EE[], Disowning<EE>> asProvider, EE e)
        {
        	assert incoming != null;

			final Disowning<EE> set = asProvider.apply(incoming);

        	final boolean removed = set.remove(e);
            incoming = set.disown();
            return removed;
        }

        /**
         * .
         *
         * @param e
         */
        public final boolean removeOutgoingEdge(Function<EE[], Disowning<EE>> asProvider, EE e)
        {
        	assert outgoing != null;

			final Disowning<EE> set = asProvider.apply(outgoing);

        	final boolean removed = set.remove(e);
            outgoing = set.disown();
            return removed;
        }

		@Override
        public void removeIncomingEdges(Function<EE[], Disowning<EE>> asProvider) {
        	final Disowning<EE> set = asProvider.apply(incoming);
        	set.clear();
        	incoming = set.disown();
        }

		@Override
        public void removeOutgoingEdges(Function<EE[], Disowning<EE>> asProvider) {
        	final Disowning<EE> set = asProvider.apply(outgoing);
        	set.clear();
        	outgoing = set.disown();
        }
    }




        private static final String NOT_IN_DIRECTED_GRAPH =
            "no such operation in a directed graph";

        /**
         * @see Graph#getAllEdges(Object, Object)
         */
        @Override
        public Set<E> getAllEdges(V sourceVertex, V targetVertex)
        {
            DirectedEdgeContainer<E,E[]> ec = getEdgeContainer(sourceVertex);
            if (ec == null) return null;

            Set<E> outgoing = arraySetProvider.apply(ec.outgoing());
            final Set<E> edges = new ArrayUnenforcedSet<E>(outgoing.size());

            for (E e : outgoing) {
            	if (getEdgeTarget(e).equals(targetVertex)) {
            		edges.add(e);
            	}
            }

            return edges;

        }

        /**
         * @see Graph#getEdge(Object, Object)
         */
        @Override
        public E getEdge(V sourceVertex, V targetVertex)
        {
            if (containsVertex(sourceVertex)
                && containsVertex(targetVertex))
            {
            	DirectedEdgeContainer<E, E[]> ec = getEdgeContainer(sourceVertex);

            	Set<E> outgoing = arraySetProvider.apply(ec.outgoing());
                Iterator<E> iter = outgoing.iterator();

                while (iter.hasNext()) {
                    E e = iter.next();

                    if (getEdgeTarget(e).equals(targetVertex)) {
                        return e;
                    }
                }
            }

            return null;
        }

        @Override
        public boolean containsEdge(E edge) {
            final Set<E> outgoing = arraySetProvider.apply(vertexMap.get(edge.getSource()).outgoing());
            return outgoing.contains(edge);
        }

        private void addEdgeToTouchingVertices(E e)
        {
            V source = getEdgeSource(e);
            V target = getEdgeTarget(e);

            getEdgeContainer(source).addOutgoingEdge(arraySetProvider, e);
            getEdgeContainer(target).addIncomingEdge(arraySetProvider, e);
        }

        /**
         * @see UndirectedGraph#degreeOf(Object)
         */
        public int degreeOf(V vertex)
        {
            throw new UnsupportedOperationException(NOT_IN_DIRECTED_GRAPH);
        }

        /**
         * @see Graph#edgesOf(Object)
         */
        @Override
        public Set<E> edgesOf(V vertex)
        {
        	Set<E> incoming = arraySetProvider.apply(getEdgeContainer(vertex).incoming());
        	Set<E> outgoing = arraySetProvider.apply(getEdgeContainer(vertex).outgoing());
            Set<E> inAndOut =
                new HashSet<E>(incoming.size() + outgoing.size());
            inAndOut.addAll(incoming);
            inAndOut.addAll(outgoing);

            return Collections.unmodifiableSet(inAndOut);
        }

        /**
         * @see DirectedGraph#inDegreeOf(Object)
         */
        public int inDegreeOf(V vertex)
        {
        	Set<E> incoming = arraySetProvider.apply(getEdgeContainer(vertex).incoming());
            return incoming.size();
        }

        /**
         * @see DirectedGraph#incomingEdgesOf(Object)
         */
        public Set<E> incomingEdgesOf(V vertex)
        {
            return getEdgeContainer(vertex).getUnmodifiableIncomingEdges(arraySetProvider);
        }

        /**
         * @see DirectedGraph#incomingEdgesOf(Object)
         */
        public E[] incomingEdgesOfUnsafe(V vertex)
        {
        	final DirectedEdgeContainer<E, E[]> container = vertexMap.get(vertex);
        	if (container == null) return null;
        	return container.incoming();
        }

        public void removeIncomingEdgesOf(V vertex)
        {
        	final Set<E> incoming = getEdgeContainer(vertex).getUnmodifiableIncomingEdges(arraySetProvider);
        	for (E e : incoming) {
        		final boolean removedFromSource = getEdgeContainer(e.getSource()).removeOutgoingEdge(arraySetProvider, e);
        		assert removedFromSource;
        		changed = true;
        	}
        	getEdgeContainer(vertex).removeIncomingEdges(arraySetProvider);
        }

        public void removeOutgoingEdgesOf(V vertex)
        {
        	final DirectedEdgeContainer<E, E[]> container = getEdgeContainer(vertex);
        	final Set<E> outgoing = container.getUnmodifiableOutgoingEdges(arraySetProvider);
        	for (E e : outgoing) {
        		final boolean removedFromTarget = getEdgeContainer(e.getTarget()).removeIncomingEdge(arraySetProvider, e);
        		assert removedFromTarget;
        		changed = true;
        	}
        	container.removeOutgoingEdges(arraySetProvider);
        }

        /**
         * @see DirectedGraph#outDegreeOf(Object)
         */
        public int outDegreeOf(V vertex)
        {
        	Set<E> outgoing = arraySetProvider.apply(getEdgeContainer(vertex).outgoing());
            return outgoing.size();
        }




        /**
         * @see DirectedGraph#outgoingEdgesOf(Object)
         */
        public Set<E> outgoingEdgesOf(V vertex)
        {
            return getEdgeContainer(vertex).getUnmodifiableOutgoingEdges(arraySetProvider);
        }

        /**
         * @see DirectedGraph#outgoingEdgesOf(Object)
         */
        public E[] outgoingEdgesOfUnsafe(V vertex)
        {
        	final DirectedEdgeContainer<E, E[]> container = vertexMap.get(vertex);
        	if (container == null) return null;
            return container.outgoing();
        }

        private boolean removeEdgeFromTouchingVertices(E e)
        {
            V source = getEdgeSource(e);
            V target = getEdgeTarget(e);

            final boolean removedFromSource = getEdgeContainer(source).removeOutgoingEdge(arraySetProvider, e);
            final boolean removedFromTarget = getEdgeContainer(target).removeIncomingEdge(arraySetProvider, e);

            assert removedFromSource == removedFromTarget;
            changed |= removedFromSource;
            return removedFromSource;
        }

        /**
         * A lazy build of edge container for specified vertex.
         *
         * @param vertex a vertex in this graph.
         *
         * @return EdgeContainer
         */
        private DirectedEdgeContainer<E,E[]> getEdgeContainer(V vertex)
        {
            assertVertexExist(vertex);

            return vertexMap.compute(vertex, (v, ec) -> {

            if (ec == null) {
                ec = new ArraySetDirectedEdgeContainer<V, E>(classE);
            }

            return ec;
            });
        }


        public void trimToSize() {
        	// TODO: this is a hack, obviously
        	if (vertexMap instanceof SimpleVectorBase) {
        		@SuppressWarnings("rawtypes")
        		SimpleVectorBase vector = (SimpleVectorBase) vertexMap;
        		vector.trimToSize();
        	}
        }

        @Override
        public int hashCode() {
            // TODO: deriving the hashCode might be a bad idea not only because of some performance impact,
            // but also because, as of now, instances if AbstractBaseGraph (e.g.: of PDG) *are* used
            // as keys in HashMaps. This leads to fuck-ups whenever those PDGs are changed.
            // So we either have to be careful about using, e.g., IdentitiHashMaps in those cases,
            // or, in the long run, consider using some sort of hashing-by-global-id scheme.
            if (changed) {
                hashCode = super.hashCode();
            }
            return hashCode;
        }

}

// End AbstractBaseGraph.java
