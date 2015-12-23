package org.deeplearning4j.models.glove;

import lombok.NonNull;
import org.canova.api.conf.Configuration;
import org.canova.api.records.reader.impl.CSVRecordReader;
import org.canova.api.split.FileSplit;
import org.canova.api.split.InputSplit;
import org.canova.api.writable.Writable;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.models.glove.count.CountMap;
import org.deeplearning4j.models.sequencevectors.interfaces.SequenceIterator;
import org.deeplearning4j.models.sequencevectors.iterators.FilteredSequenceIterator;
import org.deeplearning4j.models.sequencevectors.iterators.SynchronizedSequenceIterator;
import org.deeplearning4j.models.sequencevectors.sequence.Sequence;
import org.deeplearning4j.models.sequencevectors.sequence.SequenceElement;
import org.deeplearning4j.models.word2vec.wordstore.VocabCache;
import org.deeplearning4j.text.sentenceiterator.BasicLineIterator;
import org.deeplearning4j.text.sentenceiterator.PrefetchingSentenceIterator;
import org.deeplearning4j.text.sentenceiterator.SentenceIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 *
 *
 *
 *
 * @author raver119@gmail.com
 */
public class AbstractCoOccurrences<T extends SequenceElement> implements Serializable {

    protected boolean symmetric;
    protected int windowSize;
    protected VocabCache<T> vocabCache;
    protected SequenceIterator<T> sequenceIterator;
    protected int workers = Runtime.getRuntime().availableProcessors();

    protected File targetFile;

    protected ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    protected long memory_threshold = 0;

    private ShadowCopyThread shadowThread;

//    private Counter<Integer> sentenceOccurrences = Util.parallelCounter();
    //private CounterMap<T, T> coOccurrenceCounts = Util.parallelCounterMap();
    private volatile CountMap<T> coOccurrenceCounts = new CountMap<>();
    //private Counter<Integer> occurrenceAllocations = Util.parallelCounter();
    //private List<Pair<T, T>> coOccurrences;
    private AtomicLong processedSequences = new AtomicLong(0);


    protected static final Logger logger = LoggerFactory.getLogger(AbstractCoOccurrences.class);

    // this method should be private, to avoid non-configured instantiation
    private AbstractCoOccurrences() {
        ;
    }

    public double getCoOccurrenceCount(@NonNull T element1, @NonNull T element2) {
        return coOccurrenceCounts.getCount(element1, element2);
    }

    /**
     * This method returns estimated memory footrpint, based on current CountMap content
     * @return
     */
    protected long getMemoryFootprint() {
        // TODO: implement this method. It should return approx. memory used by appropriate CountMap
        try {
            lock.readLock().lock();
            return ((long) coOccurrenceCounts.size()) * 24L * 5L;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * This memory returns memory threshold, defined as 1/2 of memory allowed for allocation
     * @return
     */
    protected long getMemoryThreshold() {
        return memory_threshold / 2L;
    }

    public void fit() {
        shadowThread = new ShadowCopyThread();
        shadowThread.start();

        // we should reset iterator before counting cooccurrences
        sequenceIterator.reset();

        List<CoOccurrencesCalculatorThread> threads = new ArrayList<>();
        for (int x = 0; x < workers; x++) {
            threads.add(x, new CoOccurrencesCalculatorThread(x, new FilteredSequenceIterator<T>(new SynchronizedSequenceIterator<T>(sequenceIterator), vocabCache), processedSequences));
            threads.get(x).start();
        }

        for (int x = 0; x < workers; x++) {
            try {
                threads.get(x).join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        shadowThread.finish();
        logger.info("CoOccurrences map was built: ["+ coOccurrenceCounts.size()+"]");
    }

    /**
     * Returns list of label pairs for each element met in each sequence
     * @return
     */
    @Deprecated
    private synchronized List<Pair<T, T>> coOccurrenceList() {
        /*
        if (coOccurrences != null)
            return coOccurrences;

        coOccurrences = new ArrayList<>();
        Iterator<Pair<T, T>> iterator = coOccurrenceCounts.getPairIterator();
        while (iterator.hasNext()) {
            Pair<T, T> pair = iterator.next();

            if (pair.getFirst().equals(pair.getSecond())) continue;

            // each pair should be checked against vocab, but that's not strictly required
            if (!vocabCache.hasToken(pair.getFirst().getLabel()) || !vocabCache.hasToken(pair.getSecond().getLabel())) {
//                logger.debug("Skipping pair: '"+ pair.getFirst()+"', '"+ pair.getSecond()+"'");
                continue;
            }// else logger.debug("Adding pair: '"+ pair.getFirst()+"', '"+ pair.getSecond()+"'");



            coOccurrences.add(new Pair<T, T>(pair.getFirst(), pair.getSecond()));
            if (coOccurrences.size() % 100000 == 0) logger.info("Cooccurrences gathered: " + coOccurrences.size());
        }

        return coOccurrences;
        */

        return null;
    }

    /**
     *
     *  This method returns iterator with elements pairs and their weights. Resulting iterator is safe to use in multi-threaded environment.
     *
     * Developer's note: thread safety on received iterator is delegated to PrefetchedSentenceIterator
     * @return
     */
    public Iterator<Pair<Pair<T, T>, Double>> iterator() {
        final SentenceIterator iterator;

        try {
            iterator = new PrefetchingSentenceIterator.Builder(new BasicLineIterator(targetFile))
                    .setFetchSize(500)
                    .build();
        } catch (Exception e) {
            logger.error("Target file was not found on last stage!");
            throw new RuntimeException(e);
        }
        return new Iterator<Pair<Pair<T, T>, Double>>() {
            /*
                    iterator should be built on top of current text file with all pairs
             */

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public Pair<Pair<T, T>, Double> next() {
                String line = iterator.nextSentence();
                String[] strings = line.split(" ");

                T element1 = vocabCache.elementAtIndex(Integer.valueOf(strings[0]));
                T element2 = vocabCache.elementAtIndex(Integer.valueOf(strings[1]));
                Double weight = Double.valueOf(strings[2]);

                return new Pair<>(new Pair<T, T>(element1, element2), weight);
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove() method can't be supported on read-only interface");
            }
        };
    }

    public static class Builder<T extends SequenceElement> {

        protected boolean symmetric;
        protected int windowSize = 5;
        protected VocabCache<T> vocabCache;
        protected SequenceIterator<T> sequenceIterator;
        protected int workers = Runtime.getRuntime().availableProcessors();
        protected File target;
        protected long maxmemory = Runtime.getRuntime().totalMemory();

        public Builder() {

        }

        public Builder<T> symmetric(boolean reallySymmetric) {
            this.symmetric = reallySymmetric;
            return this;
        }

        public Builder<T> windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        public Builder<T> vocabCache(@NonNull VocabCache<T> cache) {
            this.vocabCache = cache;
            return this;
        }

        public Builder<T> iterate(@NonNull SequenceIterator<T> iterator) {
            this.sequenceIterator = new SynchronizedSequenceIterator<T>(iterator);
            return this;
        }

        public Builder<T> workers(int numWorkers) {
            this.workers = numWorkers;
            return this;
        }

        /**
         * This method allows you to specify maximum memory available for CoOccurrence map builder.
         *
         * Please note: this option can be considered a debugging method. In most cases setting proper -Xmx argument set to JVM is enough to limit this algorithm.
         * Please note: this option won't override -Xmx JVM value.
         *
         * @param mbytes memory available, in GigaBytes
         * @return
         */
        // TODO: change this to GBytes after tests complete :)
        public Builder<T> maxMemory(int mbytes) {
            this.maxmemory = mbytes * 1024 * 1024 * 1024;
            return this;
        }

        /**
         * Path to save cooccurrence map after construction.
         * If targetFile is not specified, temporary file will be used.
         *
         * @param path
         * @return
         */
        public Builder<T> targetFile(@NonNull String path) {
            this.targetFile(new File(path));
            return this;
        }

        /**
         * Path to save cooccurrence map after construction.
         * If targetFile is not specified, temporary file will be used.
         *
         * @param file
         * @return
         */
        public Builder<T> targetFile(@NonNull File file) {
            this.target = file;
            return this;
        }

        public AbstractCoOccurrences<T> build() {
            AbstractCoOccurrences<T> ret = new AbstractCoOccurrences<>();
            ret.sequenceIterator = this.sequenceIterator;
            ret.windowSize = this.windowSize;
            ret.vocabCache = this.vocabCache;
            ret.symmetric = this.symmetric;
            ret.workers = this.workers;
            ret.memory_threshold = this.maxmemory;

            logger.info("Memory limit: ["+ this.maxmemory +"]");

            // use temp file, if no target file was specified
            try {
                if (this.target == null) this.target = File.createTempFile("cooccurrence", "map");
                this.target.deleteOnExit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            ret.targetFile = this.target;

            return ret;
        }
    }

    private class CoOccurrencesCalculatorThread extends Thread implements Runnable {

        private final SequenceIterator<T> iterator;
        private final AtomicLong sequenceCounter;
        private int threadId;

        public CoOccurrencesCalculatorThread(int threadId, @NonNull SequenceIterator<T> iterator, @NonNull AtomicLong sequenceCounter) {
            this.iterator = iterator;
            this.sequenceCounter = sequenceCounter;
            this.threadId = threadId;

            this.setName("CoOccurrencesCalculatorThread " + threadId);
        }

        @Override
        public void run() {
            while (iterator.hasMoreSequences()) {
                Sequence<T> sequence = iterator.nextSequence();

//                logger.info("Sequence ID: " + sequence.getSequenceId());
                // TODO: vocab filtering should take place

                List<String> tokens = new ArrayList<>(sequence.asLabels());
    //            logger.info("Tokens size: " + tokens.size());
                for (int x = 0; x < sequence.getElements().size(); x++) {
                    int wordIdx = vocabCache.indexOf(tokens.get(x));
                    if (wordIdx < 0) continue;
                    String w1 = vocabCache.wordFor(tokens.get(x)).getLabel();

                    // THIS iS SAFE TO REMOVE, NO CHANCE WE'll HAVE UNK WORD INSIDE SEQUENCE
                    /*if(w1.equals(Glove.UNK))
                        continue;
                    */

                    int windowStop = Math.min(x + windowSize + 1,tokens.size());
                    for(int j = x; j < windowStop; j++) {
                        int otherWord = vocabCache.indexOf(tokens.get(j));
                        if (otherWord < 0) continue;
                        String w2 = vocabCache.wordFor(tokens.get(j)).getLabel();

                        if(w2.equals(Glove.UNK) || otherWord == wordIdx) {
                            continue;
                        }


                        T tokenX  = vocabCache.wordFor(tokens.get(x));
                        T tokenJ = vocabCache.wordFor(tokens.get(j));
                        double nWeight = 1.0 / (j - x + Nd4j.EPS_THRESHOLD);

                        while (getMemoryFootprint() >= getMemoryThreshold()) {
                            try {
                                lock.readLock().lock();
                                int size = coOccurrenceCounts.size();
                                lock.readLock().unlock();
                                if (threadId == 0) logger.info("Memory consuimption > threshold: { size: ["+ size+ "], footrpint: ["+ getMemoryFootprint()+"], threshold: [" + getMemoryThreshold() +"] }");
                                Thread.sleep(2000);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {

                            }
                        }
                        /*
                        if (getMemoryFootprint() == 0) {
                            logger.info("Zero size!");
                        }
                        */

                        try {
                            lock.readLock().lock();
                            if (wordIdx < otherWord) {
                                coOccurrenceCounts.incrementCount(tokenX, tokenJ, nWeight);
                                if (symmetric) {
                                    coOccurrenceCounts.incrementCount(tokenJ, tokenX, nWeight);
                                }
                            } else {
                                coOccurrenceCounts.incrementCount(tokenJ, tokenX, nWeight);

                                if (symmetric) {
                                    coOccurrenceCounts.incrementCount(tokenX, tokenJ, nWeight);
                                }
                            }
                        } finally {
                            lock.readLock().unlock();
                        }
                    }
                }

                sequenceCounter.incrementAndGet();
            }
        }
    }

    /**
     * This class is designed to provide shadow copy functionality for CoOccurence maps, since with proper corpus size you can't fit such a map into memory
     *
     */
    private class ShadowCopyThread extends Thread implements Runnable {

        private AtomicBoolean isFinished = new AtomicBoolean(false);
        private AtomicBoolean isTerminate = new AtomicBoolean(false);
        private AtomicBoolean isInvoked = new AtomicBoolean(false);
        private AtomicBoolean shouldInvoke = new AtomicBoolean(false);

        // file that contains resuts from previous runs
        private File latestFile;

        public ShadowCopyThread() {

            this.setName("ACO ShadowCopy thread");
        }

        @Override
        public void run() {
            /*
                  Basic idea is pretty simple: run quetly, untill memory gets filled up to some high volume.
                  As soon as this happens - execute shadow copy.
            */
            while (!isFinished.get() && !isTerminate.get()) {
                // check used memory. if memory use below threshold - sleep for a while. if above threshold - invoke copier


                // TODO: fix these megabytes
                if (getMemoryFootprint() > getMemoryThreshold()  || (shouldInvoke.get() && !isInvoked.get())) {
                    // we'll just invoke copier, nothing
                    invokeBlocking();
                } else {
                    try {
                        lock.readLock().lock();
                        int size = coOccurrenceCounts.size();
                        lock.readLock().unlock();
                        //logger.info("Current memory situation: {size: [" +size+ "], footprint: [" + getMemoryFootprint()+"], threshold: ["+ getMemoryThreshold() +"]}");
                        Thread.sleep(100);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        /**
         * This methods advises shadow copy process to start
         */
        public void invoke() {
            shouldInvoke.compareAndSet(false, true);
        }

        /**
         * This methods dumps cooccurrence map into save file.
         * Please note: this method is synchronized and will block, until complete
         */
        public synchronized void invokeBlocking() {
            if (getMemoryFootprint() < getMemoryThreshold() && !isFinished.get()) return;

            int numberOfLinesSaved = 0;

            isInvoked.set(true);

            logger.info("invokeBlocking() started.");

            /*
                Basic plan:
                    1. Open temp file
                    2. Read that file line by line
                    3. For each read line do synchronization in memory > new file direction
             */


            CountMap<T> localMap;
            try {
                // in any given moment there's going to be only 1 WriteLock, due to invokeBlocking() being synchronized call
                lock.writeLock().lock();



                // obtain local copy of CountMap
                 localMap = coOccurrenceCounts;

                // set new CountMap, and release write lock
                coOccurrenceCounts = new CountMap<T>();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                lock.writeLock().unlock();
            }

            try {

                // if latestFile defined - use it. Create new temp file otherwise
                File currentFile = null;
                if (latestFile == null) {
                    logger.info("Creating new temp currentFile");
                    currentFile = File.createTempFile("acod", "tmp");
                    currentFile.deleteOnExit();
                } else {
                    currentFile =  latestFile;
                }

                File file = null;
                if (!isFinished.get()) {
                    file = File.createTempFile("aco", "tmp");
                    file.deleteOnExit();
                } else file = targetFile;


                PrintWriter pw = new PrintWriter(file);

                InputSplit split = new FileSplit(currentFile);
                Configuration canovaConf = new Configuration(true);

                CSVRecordReader reader = new CSVRecordReader();
                reader.initialize(canovaConf, split);

                while (reader.hasNext()) {
                    List<Writable> list = new ArrayList<>(reader.next());

                    // first two elements are integers - vocab indexes
                    T element1 = vocabCache.wordFor(vocabCache.wordAtIndex(list.get(0).toInt()));
                    T element2 = vocabCache.wordFor(vocabCache.wordAtIndex(list.get(1).toInt()));

                    // getting third element, previously stored weight
                    double sWeight = list.get(2).toDouble();

                    // now, since we have both elements ready, we can check this pair against inmemory map
                        double mWeight = localMap.getCount(element1, element2);
                        if (mWeight <= 0) {
                            // this means we have no such pair in memory, so we'll do nothing to sWeight
                        } else {
                            // since we have new weight value in memory, we should update sWeight value before moving it off memory
                            sWeight += mWeight;

                            // original pair can be safely removed from CountMap
                            localMap.removePair(element1,element2);
                        }

                        StringBuilder builder = new StringBuilder().append(element1.getIndex()).append(" ").append(element2.getIndex()).append(" ").append(sWeight);
                        pw.println(builder.toString());
                        numberOfLinesSaved++;
                }

                //now, we can dump the rest of elements, which were not presented in existing dump
                Iterator<Pair<T, T>> iterator = localMap.getPairIterator();
                while (iterator.hasNext()) {
                    Pair<T, T> pair = iterator.next();
                    double mWeight = localMap.getCount(pair);

                    StringBuilder builder = new StringBuilder().append(pair.getFirst().getIndex()).append(" ").append(pair.getFirst().getIndex()).append(" ").append(mWeight);
                    pw.println(builder.toString());
                    numberOfLinesSaved++;
                }

                pw.flush();
                pw.close();

                // just a hint for gc
                latestFile = currentFile;

                localMap = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            logger.info("invokeBlocking() finished. Number of lines saved: [" + numberOfLinesSaved + "]");
            isInvoked.set(false);
        }

        /**
         * This method provides soft finish ability for shadow copy process.
         * Please note: it's blocking call, since it requires for final merge.
         */
        public void finish() {
            if (this.isFinished.get()) return;

            this.isFinished.set(true);
            invokeBlocking();
        }

        /**
         * This method provides hard fiinish ability for shadow copy process
         */
        public void terminate() {
            this.isTerminate.set(true);
        }
    }
}