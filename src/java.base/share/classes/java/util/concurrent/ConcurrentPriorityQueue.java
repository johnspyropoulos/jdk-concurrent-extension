 package java.util.concurrent;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe concurrent priority queue based on a skip list implementation.
 * <p>
 * This queue maintains elements in a priority order, where the smallest element
 * (according to the specified comparator or the elements' natural ordering) is always at the head.
 * Elements can be safely inserted and removed concurrently by multiple threads without locking.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class ConcurrentPriorityQueue<E> extends AbstractQueue<E> {
    
    private static final class Node<E> {
        final E item;
        final int topLevel;
        final AtomicReference<Node<E>>[] next;

        AtomicBoolean markedForDelete;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Node(E item, int height) {
            this.item = item;
            this.topLevel = height;
            this.next = (AtomicReference<Node<E>>[]) new AtomicReference[height + 1];
            
            markedForDelete = new AtomicBoolean(false);

            for (int i = 0; i <= height; i++)
                this.next[i] = new AtomicReference<Node<E>>(null);
        }
    }

    private static final int MAX_BACKOFF = 128;

    private final int TOTAL_LEVELS = 32;
    private final int MAX_LEVEL = TOTAL_LEVELS - 1;

    private final Comparator<? super E> comparator;

    private final Node<E> sentinelHead;

    private AtomicInteger totalItems = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    private int compare(E a, E b) {
        return (comparator != null) ? comparator.compare(a, b) : ((Comparable<? super E>) a).compareTo(b);
    }

    // geometric distribution for node level assignment
    private int randomLevel() {
        int level = 0;
        while (ThreadLocalRandom.current().nextInt(2) == 0 && level < MAX_LEVEL)
            level++;

        return level;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void listInsert(E item) {
        final Node<E> node = new Node<>(item, randomLevel());

        Node<E>[] preds = (Node<E>[]) new Node[TOTAL_LEVELS];
        Node<E>[] succs = (Node<E>[]) new Node[TOTAL_LEVELS];

        int backoff = 1;

        // insert in level 0
        while (true) {
            Node<E> pred = sentinelHead;

            // find predecessors and successors for all levels
            for (int level = MAX_LEVEL; level >= 0; level--) {
                Node<E> curr = pred.next[level].get();

                while (curr != null && compare(curr.item, item) < 0) {
                    // If the node is marked for delete, attempt to unlink it from the list.
                    // If unlinking fails, do nothing. If it failed because another thread
                    // unlinked it first, then removal is done. If it failed because a node
                    // that is the direct predecessor of the marked node, it should be handled
                    // later, as these cases can't be distinguished.
                    if (curr.markedForDelete.get() == true)
                        pred.next[level].compareAndSet(curr, curr.next[level].get());
                    else
                        pred = curr;
                    
                    curr = pred.next[level].get();
                }

                preds[level] = pred;
                succs[level] = curr;
            }

            node.next[0].set(succs[0]);
            if (preds[0].next[0].compareAndSet(succs[0], node)) {
                totalItems.incrementAndGet();
                break;
            }

            // Exponential backoff
            for (int i = 0; i < backoff; i++)
                Thread.onSpinWait();

            backoff = Math.min(backoff * 2, MAX_BACKOFF);
            // if insertion failed, retry entire insert
        }

        backoff = 1;

        // insert in higher levels
        for (int level = 1; level <= node.topLevel; level++) {
            while (true) {
                node.next[level].set(succs[level]);
                if (preds[level].next[level].compareAndSet(succs[level], node)) {
                    backoff = 1;
                    break;
                }

                // Exponential backoff
                for (int i = 0; i < backoff; i++)
                    Thread.onSpinWait();

                backoff = Math.min(backoff * 2, MAX_BACKOFF);

                // if insertion failed, find again predecessors
                // and successors only on current level
                Node<E> pred = sentinelHead;
                Node<E> curr = pred.next[level].get();
                while (curr != null && compare(curr.item, item) < 0) {
                    // following the same logic as above
                    if (curr.markedForDelete.get() == true)
                        pred.next[level].compareAndSet(curr, curr.next[level].get());
                    else
                        pred = curr;
                    
                    curr = pred.next[level].get();
                }

                preds[level] = pred;
                succs[level] = curr;
            }
        }
    }

    private E listRemoveMin() {
        int backoff = 1;

        while (true) {
            Node<E> head = sentinelHead.next[0].get();
            if (head == null)
                return null;
            
            // Attempt to mark the head as deleted. If it fails,
            // then it's already been marked by another thread, so
            // we can attempt to unlink it
            if (!head.markedForDelete.compareAndSet(false, true)) {
                sentinelHead.next[0].compareAndSet(head, head.next[0].get());
                
                // Exponential backoff
                for (int i = 0; i < backoff; i++)
                    Thread.onSpinWait();

                backoff = Math.min(backoff * 2, MAX_BACKOFF);
                continue;
            }

            // If we marked the head as deleted, attempt to delete it.
            // If the deletion failed, we do nothing as it was probably deleted
            // by another thread
            sentinelHead.next[0].compareAndSet(head, head.next[0].get());
            totalItems.decrementAndGet();
            return head.item;
        }
    }

    private E listFindMin() {
        while (true) {
            Node<E> head = sentinelHead.next[0].get();
            
            // traverse level 0 until the first unmarked node is found
            while (head != null && head.markedForDelete.get() == true) {
                // for every marked for delete node that we find, attempt to
                // unlink it. If it fails, it's probably been removed by another thread
                sentinelHead.next[0].compareAndSet(head, head.next[0].get());
                head = head.next[0].get();
            }

            if (head == null)
                return null;

            // return the item only if it hasn't been marked for delete
            if (head.markedForDelete.get() == false)
                return head.item;

            // else repeat the entire process
        }
    }

    /**
     * Constructs a {@code ConcurrentPriorityQueue} that orders its elements according to
     * their {@linkplain Comparable natural ordering}.
     */
    public ConcurrentPriorityQueue() {
        this(null);
    }

    /**
     * Constructs a {@code ConcurrentPriorityQueue} that orders its elements according to
     * the specified comparator.
     *
     * @param comparator the comparator that will be used to order this queue.
     *                   If {@code null}, the {@linkplain Comparable natural ordering}
     *                   of the elements will be used.
     */
    public ConcurrentPriorityQueue(Comparator<? super E> comparator) {
        this.comparator = comparator;
        this.sentinelHead = new Node<>(null, MAX_LEVEL);
    }

    /**
     * Inserts the specified element into this priority queue.
     * The queue will be reordered to maintain priority constraints.
     *
     * @param item the element to add
     * @return {@code true} (as specified by {@link java.util.Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    @Override
    public boolean offer(E item) {
        if (item == null)
            throw new NullPointerException("item cannot be null");

        listInsert(item);
        return true;
    }

    /**
     * Retrieves, but does not remove, the head of this queue, or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public E peek() {
        return listFindMin();
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public E poll() {
        return listRemoveMin();
    }

    /**
     * Returns the number of elements in this queue.
     * This count is only an approximation because of concurrent insertions and removals.
     *
     * @return the number of elements in this queue
     */
    @Override
    public int size() {
        return totalItems.get();
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    @Override
    public boolean isEmpty() {
        return totalItems.get() == 0;
    }

    /**
     * Adds an element to the queue. This is equivalent to {@link #offer(Object)}.
     *
     * @param item the element to add
     * @return {@code true} (as specified by {@link java.util.Collection#add})
     * @throws NullPointerException if the specified element is {@code null}
     */
    @Override
    public boolean add(E item) {
        return offer(item);
    }

    /**
     * Not supported. This queue does not support arbitrary element removal.
     *
     * @param o the object to be removed
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("ConcurrentPriorityQueue does not support 'remove'");
    }

    /**
     * Not supported. This queue does not support bulk removal operations.
     *
     * @param c the collection containing elements to be removed from this queue
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentPriorityQueue does not support 'removeAll'");
    }

    /**
     * Not supported. This queue does not support bulk retention operations.
     *
     * @param c the collection containing elements to be retained in this queue
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentPriorityQueue does not support 'retainAll'");
    }

    /**
     * Returns an iterator over the elements in this priority queue in ascending order of priority.
     * <p>
     * The returned iterator is <strong>weakly consistent</strong>: it does not throw
     * {@link java.util.ConcurrentModificationException} and may reflect some, all, or none of the
     * changes made to the queue after the iterator was created.
     * </p>
     *
     * @return an iterator over the elements in this queue, excluding logically deleted entries
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {

        private Node<E> currentNode;

        public Itr() {
            currentNode = sentinelHead.next[0].get();
        }

        @Override
        public boolean hasNext() {
            Node<E> node = currentNode;

            while (node != null && node.markedForDelete.get())
                node = node.next[0].get();

            return node != null;
        }

        @Override
        public E next() {
            while (currentNode != null && currentNode.markedForDelete.get() == true)
                currentNode = currentNode.next[0].get();

            if (currentNode == null)
                throw new NoSuchElementException();

            E item = currentNode.item;
            currentNode = currentNode.next[0].get();
            return item;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("ConcurrentPriorityQueue Iterator does not support 'remove'");
        }
    }
    
}
