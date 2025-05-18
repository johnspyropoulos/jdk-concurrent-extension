package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A general-purpose LIFO (Last-In-First-Out) data structure that fully supports concurrent operations.
 *
 * <p>
 * This implementation is thread-safe and lock-free.
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
     * Constructs an empty {@code ConcurrentStack}
     */
    public ConcurrentStack() {}

    /**
     * Pushes an item onto the top of the stack.
     *
     * @param item the element to be added
     */
    public void push(E item) {
        Node<E> oldTop;
        final Node<E> newTop = new Node<E>(item);

        do {
            oldTop = top.get();
            newTop.down = oldTop;
        } while (!top.compareAndSet(oldTop, newTop));

        totalItems.incrementAndGet();
    }

    /**
     * Removes the top item of the stack
     * 
     * @return the removed item
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
     * Gets the item at the top of the stack
     * 
     * @return item at the top of the stack
     */
    public E peek() {
        return top.get().item;
    }

    /**
     * Returns the total amount of items in the stack.
     * <p>
     * Note that it may by slightly innacurate during contention
     * 
     * @return an integer that represents the total items in the stack (approximate)
     */
    @Override
    public int size() {
        return totalItems.get();
    }

    /**
     * Removes all items from the stack
     */
    @Override
    public void clear() {
        top.set(null);
        totalItems.set(0);
    }

    /**
     * {@code push} synonym
     * 
     * @param item item to push
     */
    @Override
    public boolean add(E item) {
        push(item);
        return true;
    }

    /**
     * Unsupported operation
     * 
     * @throws UnsupportedOperationException when called
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'remove'");
    }

    /**
     * Unsupported operation
     * 
     * @throws UnsupportedOperationException when called
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'removeAll'");
    }

    /**
     * Unsupported operation
     * 
     * @throws UnsupportedOperationException when called
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("ConcurrentStack does not support 'retainAll'");
    }

    /**
     * Returns an iterator over the elements in this stack in LIFO (last-in-first-out) order.
     * <p>
     * The iterator provides a weakly consistent view of the stack, meaning it reflects some 
     * state of the stack at or since the creation of the iterator. It does not throw 
     * {@link java.util.ConcurrentModificationException} if the stack is modified concurrently.
     * <p>
     * The returned iterator does not support element removal.
     *
     * @return an iterator over the elements in this stack, starting from the top
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
