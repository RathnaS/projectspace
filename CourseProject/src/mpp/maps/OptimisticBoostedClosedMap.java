package mpp.maps;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import mpp.benchmarks.ClosedMapThread;
import mpp.exception.AbortedException;

public class OptimisticBoostedClosedMap implements IntMap<Integer,Object> {

	private static final int THRESHHOLD = 10;
	final int PUT = 1;
	final int GET = 2;
	final int CONTAINS = 3;
	final int REMOVE = 4;
	
	public volatile BucketList<OBNode>[] bucket;
	public AtomicInteger bucketSize;
	public AtomicInteger setSize;
	
	public OptimisticBoostedClosedMap(int capacity) {
		// TODO Auto-generated constructor stub
		bucket = new BucketList[capacity];
		bucket[0] = new BucketList<>();
		bucketSize = new AtomicInteger(capacity);
		setSize = new AtomicInteger(0);
	}
	
	public class ReadSetEntry {
		OBNode pred;
		OBNode curr;
		boolean checkLink;
		
		public ReadSetEntry(OBNode pred, OBNode curr, boolean checkLink) {
			this.pred = pred;
			this.curr = curr;
			this.checkLink = checkLink;
		}	
	}
	
	public class WriteSetEntry {
//		int item;
		OBNode pred;
		OBNode curr;
		OBNode newNode;
		int operation;
		int key;
		Object value;
		
		public WriteSetEntry(OBNode pred, OBNode curr, int operation, int key,Object value) {
			this.pred = pred;
			this.curr = curr;
			this.operation = operation;
			this.key = key;
			this.value = value;
		}
	}
	
	public boolean put(Integer k,Object v) throws AbortedException{
		return ((boolean)operation(PUT,new OBNode(k, v)));
	}
	
	public Object remove(Integer k) throws AbortedException {
		return operation(REMOVE,new OBNode(k,null) );
	}
	
	public Object get(Integer k) throws AbortedException {
		return operation(GET,new OBNode(k,null) );
	}
	
	public boolean contains(Integer k) throws AbortedException {
		return ((boolean)operation(CONTAINS,new OBNode(k,null)));
	}
	
	private Object operation(int type,OBNode myNode) throws AbortedException{
		
		TreeMap<Integer, WriteSetEntry> writeset = ((ClosedMapThread) Thread.currentThread()).list_writeset;
		
		WriteSetEntry entry = writeset.get(myNode.key);
		
		if(entry != null){
			if(entry.operation == PUT){
				if(type == PUT)
					return false;
				else if(type == CONTAINS){
//					return entry.value;		//returning the mapped object
					return true;
				}else{
					if(type == REMOVE)
						writeset.remove(myNode.key);
					return entry.value;
				}
			}else{
				if( type == CONTAINS)
					return false;
				else if(type == REMOVE || type == GET){
					return null;	// returning a null object
				}else{
					writeset.remove(myNode.key);
					return true;
				}
			}
		}
		
		ArrayList<ReadSetEntry> readset = ((ClosedMapThread) Thread.currentThread()).list_readset;
		
		// this is where it differs from actual set
		// will help provide constant time operations
		int myBucket = new Integer(myNode.key).hashCode() % bucketSize.get();
		BucketList<OBNode> b = getBucketList(myBucket);						
		
		Window window = b.find(b.head, b.makeOrdinaryKey(myNode.key));
		
		OBNode pred = window.pred;
		OBNode curr = window.curr;
		
		int currKey = curr.key;
		
		boolean[] marked = {false};
		OBNode succ = curr.next.get(marked);
		boolean currMarked = marked[0];
		
		if(!postValidate(readset))
			throw AbortedException.abortedException;
		
		if(currKey == myNode.key && !currMarked){
			if(type == CONTAINS){
				readset.add(new ReadSetEntry(pred, curr, false));
				return true;
			}else if(type == PUT){
				readset.add(new ReadSetEntry(pred, curr, false));
				return false;
			}else{
				readset.add(new ReadSetEntry(pred, curr, true));
				
				if(type == REMOVE)
					writeset.put(myNode.key, new WriteSetEntry(pred, curr, REMOVE, myNode.key,myNode.value));
				
				return curr.value;
			}
		}else{
			readset.add(new ReadSetEntry(pred, curr, true));
			if(type == CONTAINS)
				return false;
			else if(type == GET || type == REMOVE){
				return null;
			}else{
				writeset.put(myNode.key, new WriteSetEntry(pred, curr, PUT, myNode.key,myNode.value));
				return true;
			}
		}
	}
	
	private boolean postValidate(ArrayList<ReadSetEntry> readset) {
		ReadSetEntry entry;	
		int size = readset.size();

		int [] predLocks = new int[size];
		int [] currLocks = new int[size];
		
		// get snapshot of lock values
		for(int i=0; i<size; i++)
		{
			entry = readset.get(i);
			predLocks[i] = entry.pred.lock.get();
			currLocks[i] = entry.curr.lock.get();
		}
		
		// check the values of the nodes 
		// and also check that nodes are not currently locked
		
		boolean[] marked = {false};
		
		for(int i=0; i<size; i++)
		{
			entry = readset.get(i);
			
			OBNode succ = entry.curr.next.get(marked);
			
			if((currLocks[i] & 1) == 1 || marked[0])
				return false;
			
			OBNode p_succ = entry.pred.next.get(marked);
			
			if(entry.checkLink)
			{
				if((predLocks[i] & 1) == 1 || marked[0] || entry.curr != entry.pred.next.getReference()) 
					return false;
			}
		}
		
		// check that lock values are still the same since validation starts
		for(int i=0; i<size; i++)
		{
			entry = readset.get(i);
			if(currLocks[i] != entry.curr.lock.get())
				return false;
			if(entry.checkLink && predLocks[i] != entry.pred.lock.get())
				return false;
		}
		return true;
	}
	
	private boolean commitValidate(ArrayList<ReadSetEntry> readset){
		
		ReadSetEntry entry;	
		int size = readset.size();

		boolean[] marked = {false};
		
		// check the values of the nodes 
		for(int i=0; i<size; i++)
		{
			entry = readset.get(i);
			
			OBNode succ = entry.curr.next.get(marked);
			
			if(marked[0])
				return false;
			
			OBNode p_succ = entry.pred.next.get(marked);
			
			if(entry.checkLink && (marked[0] || entry.curr != entry.pred.next.getReference()))
				return false;
		}
		return true;
		
	}

	@Override
	public void commit() throws AbortedException {
		ClosedMapThread t = ((ClosedMapThread) Thread.currentThread());
		
		Set<Entry<Integer, WriteSetEntry>> write_set = t.list_writeset.entrySet();
		ArrayList<ReadSetEntry> read_set = t.list_readset;
		
		// read-only transactions do nothing
		if(write_set.isEmpty())
		{
			read_set.clear();
			return;
		}
		
		long threadId = Thread.currentThread().getId();
		Iterator<Entry<Integer, WriteSetEntry>> iterator = write_set.iterator();
		WriteSetEntry entry;
		
		int predLock, currLock;
		OBNode newNodeOrVictim;
		
		while(iterator.hasNext())
		{
			entry = iterator.next().getValue();
			
			predLock = entry.pred.lock.get();
			currLock = entry.curr.lock.get();
			
			if((predLock & 1) == 1 && entry.pred.lockHolder != threadId)
				throw AbortedException.abortedException;
			// if operation is REMOVE, check that curr lock is not acquired by another thread
			if(entry.operation == REMOVE && (currLock & 1) == 1 && entry.curr.lockHolder != threadId)
				throw AbortedException.abortedException;
			
			// Try to acquire pred lock
			if(entry.pred.lockHolder == threadId || entry.pred.lock.compareAndSet(predLock, predLock + 1)){
							// if operation is REMOVE, try to acquire curr lock
				entry.pred.lockHolder = threadId;
				
				if(entry.operation == REMOVE){
					if(entry.curr.lockHolder == threadId || entry.curr.lock.compareAndSet(currLock, currLock + 1))
						entry.curr.lockHolder = threadId;
						// in case of failure, unlock pred and abort.
					else{
						entry.pred.lockHolder = -1;
						entry.pred.lock.decrementAndGet();
						throw AbortedException.abortedException;
					}
				}
			}else
				throw AbortedException.abortedException;
		}
		
		if(!commitValidate(t.list_readset))
			throw AbortedException.abortedException;

		// Publish write-set
		iterator = write_set.iterator();
		while(iterator.hasNext())
		{
			entry = iterator.next().getValue();
			
			OBNode pred = entry.pred;
			OBNode curr = entry.pred.next.getReference();
			while(curr.key < entry.key){
				pred = curr;
				curr = curr.next.getReference();
			}
			
			if(entry.operation == PUT){
				newNodeOrVictim = new OBNode(entry.key,entry.value);
				newNodeOrVictim.lock.set(1);
				newNodeOrVictim.lockHolder = threadId;
				entry.newNode = newNodeOrVictim;
				newNodeOrVictim.next.set(curr,false);
				pred.next.set(newNodeOrVictim,false);
			}else{
				curr.next.attemptMark(curr.next.getReference(), true);
				pred.next = entry.curr.next;
			}			
		}
		
		// unlock
		iterator = write_set.iterator();
		while(iterator.hasNext()){
			
			entry = iterator.next().getValue();
			
			if(entry.pred.lockHolder == threadId){
				entry.pred.lockHolder = -1;
				entry.pred.lock.incrementAndGet();
			}
			// newNodeOrVictim in this case is either the added or the removed node 
			if(entry.operation == REMOVE)
				newNodeOrVictim = entry.curr;
			else // add
				newNodeOrVictim = entry.newNode;
			if (newNodeOrVictim.lockHolder == threadId) {
				newNodeOrVictim.lockHolder = -1;
				newNodeOrVictim.lock.incrementAndGet();
			}
		}
		
		// clear read- and write- sets
		read_set.clear();
		write_set.clear();
	}

	@Override
	public void abort() {
		// TODO Auto-generated method stub
		ClosedMapThread t = ((ClosedMapThread)Thread.currentThread());
		
		Iterator<Entry<Integer, WriteSetEntry>> iterator = t.list_writeset.entrySet().iterator();
		WriteSetEntry entry;
		
		while(iterator.hasNext())
		{
			entry = iterator.next().getValue();
			if(entry.pred.lockHolder == t.getId())
			{
				entry.pred.lockHolder = -1;
				entry.pred.lock.decrementAndGet();
			}
				
			if(entry.operation == REMOVE && entry.curr.lockHolder == t.getId())
			{
				{
					entry.curr.lockHolder = -1;
					entry.curr.lock.decrementAndGet();
				}
			}
		}
		
		t.list_readset.clear();
		t.list_writeset.clear();
	}

	@Override
	public boolean nonTransactionalPut(Integer k, Object v) {
		int myBucket = k.hashCode() % bucketSize.get();
		
		BucketList<OBNode> b = getBucketList(myBucket);
		
		if(!b.add(new OBNode(k, v)))
			return false;
		
//		resize();
		
		return true;
	}

	private BucketList<OBNode> getBucketList(int myBucket){
		if(bucket[myBucket] == null)
			initializeBucket(myBucket);
		
//		System.out.println(bucket[myBucket].head.next.getReference().value);
		
		return bucket[myBucket];
	}
	
	private void initializeBucket(int myBucket){
		int parent = getParent(myBucket);
		if(bucket[parent] == null)
			initializeBucket(parent);
		BucketList<OBNode> b = bucket[parent].getSentinel(myBucket);
		if(b != null)
			bucket[myBucket] = b;
	}
	
	private int getParent(int myBucket){
		int parent = bucketSize.get();
		do{
			parent = parent >> 1;
		}while(parent > myBucket);
		parent = myBucket - parent;
		return parent;
	}
	
	private void resize(){
		int setSizeNow = setSize.getAndIncrement();
		int bucketSizeNow = bucketSize.get();
		
		if(setSizeNow / bucketSizeNow > THRESHHOLD)
			bucketSize.compareAndSet(bucketSizeNow, 2 * bucketSizeNow);
		
	}
}
