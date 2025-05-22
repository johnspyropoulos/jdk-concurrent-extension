package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe, non-blocking stack implementation based on atomic operations.
 * <p>
 * This stack uses a Treiber stack structure with an {@link AtomicReference} to ensure
 * lock-free, LIFO (last-in-first-out) access. It supports concurrent push and pop operations
 * from multiple threads without requiring explicit synchronization.
 * </p>
 *
 * @param <E> the type of elements held in this stack
 */
public class ConcurrentStack<E> extends AbstractCollection<E> {

    private static final class Node<E> {
        final E item;
        Node<E> down;

        public Node(E item) {
            this.item = item;
        }
    }

    private AtomicReference<Node<E>> top = new AtomicReference<>(null);

    private AtomicInteger totalItems = new AtomicInteger(0);

    /**
     * Constructs an empty {@code ConcurrentStack}.
     */
    public ConcurrentStack() {}

    /**
     * Pushes an element onto the top of this stack.
     *
     * @param item the element to push
     * @throws NullPointerException if the specified element is {@code null}
     */
    public void push(E item) {
        if (item == null)
            throw new NullPointerException("item cannot be null");

        Node<E> oldTop;
        final Node<E> newTop = new Node<E>(item);

        do {
            oldTop = top.get();
            newTop.down = oldTop;
        } while (!top.compareAndSet(oldTop, newTop));

        totalItems.incrementAndGet();
    }

    /**
     * Removes and returns the element at the top of the stack.
     *
     * @return the element removed from the top of the stack
     * @throws EmptyStackException if the stack is empty
     */
    public E pop() {
        Node<E> oldTop;
        Node<E> newTop;

        do {
            oldTop = top.get();

            if (oldTop == null)
                throw new EmptyStackException();

            newTop = oldTop.down;
        } while (!top.compareAndSet(oldTop, newTop));

        totalItems.decrementAndGet();

        return oldTop.item;
    }

    /**
     * Retrieves, but does not remove, the element at the top of the stack.
     *
     * @return the element at the top of the stack
     * @throws EmptyStackException if the stack is empty
     */
    public E peek() {
        return top.get().item;
    }

    /**
     * Returns the number of elements in the stack.
     *
     * @return the number of elements in this stack
     */
    @Override
    public int size() {
        return totalItems.get();
    }

    /**
     * Removes all of the elements from this stack.
     * The stack will be empty after this call returns.
     */
    @Override
    public void clear() {
        top.set(null);
        totalItems.set(0);
    }

    /**
     * Adds an element to the stack. This is equivalent to {@link #push(Object)}.
     *
     * @param item the element to add
     * @return {@code true} (as specified by {@link java.util.Collection#add})
     * @throws NullPointerException if the specified element is {@code null}
     */
    @Override
    public boolean add(E item) {
        push(item);
        return true;
    }

    /**
     * Not supported. This stack does not support arbitrary element removal.
     *
     * @param o the object to be removed
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'remove'");
    }

    /**
     * Not supported. This stack does not support bulk removal operations.
     *
     * @param c the collection containing elements to be removed from this stack
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'removeAll'");
    }

    /**
     * Not supported. This stack does not support bulk retention operations.
     *
     * @param c the collection containing elements to be retained in this stack
     * @return never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'retainAll'");
    }

    /**
     * Returns an iterator over the elements in this stack in LIFO order.
     * <p>
     * The iterator is weakly consistent and does not throw {@link java.util.ConcurrentModificationException}.
     * It reflects the state of the stack at some point during or after the creation of the iterator.
     * </p>
     *
     * @return an iterator over the elements in this stack
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    private class Itr implements Iterator<E> {

        private Node<E> currentNode;

        public Itr() {
            currentNode = top.get();
        }

        @Override
        public boolean hasNext() {
            return currentNode != null;
        }

        @Override
        public E next() {
            if (currentNode == null)
                throw new NoSuchElementException();

            E item = currentNode.item;
            currentNode = currentNode.down;
            return item;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("ConcurrentStack Iterator does not support 'remove'");
        }
    }

}
