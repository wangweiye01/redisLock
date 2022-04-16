package cc.wangweiye.redislock;

import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.RedissonMultiLock;
import org.redisson.RedissonRedLock;
import org.redisson.api.*;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class RedisLockApplicationTests {
    @Autowired
    RedissonClient redissonClient;

    @Test
    void contextLoads() {
        System.out.println(redissonClient);
    }

    /**
     * 可重入锁(可阻塞)
     *
     * @throws InterruptedException
     */
    @Test
    void lock() throws InterruptedException {
        RLock lock = redissonClient.getLock("lock");
        // 尝试获得锁，最长等待20秒，锁的租期为9秒
        lock.tryLock(20, 9, TimeUnit.SECONDS);

        Thread t = new Thread(() -> {
            RLock lock1 = redissonClient.getLock("lock");
            System.out.println("子线程尝试获得锁...");
            try {
                boolean b = lock1.tryLock(20, 9, TimeUnit.SECONDS);
                if (b) System.out.println("子线程获得锁成功");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock1.unlock();
            }
        });

        t.start();
        t.join();
    }

    /**
     * 倒计时锁(闭锁)
     *
     * @throws InterruptedException
     */
    @Test
    void countDown() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        final RCountDownLatch latch = redissonClient.getCountDownLatch("latch1");
        latch.trySetCount(2);

        executor.execute(() -> {
            try {
//                long waitSeconds = 3;
//                System.out.println(String.format("教育要关门了，还有%d个学生没有走，我只等你们%d秒", latch.getCount(), waitSeconds));
//                latch.await(waitSeconds, TimeUnit.SECONDS);
                System.out.println(String.format("教室要关门了，还有%d个学生没有走，我一直等你们", latch.getCount()));
                latch.await();
                System.out.println(String.format("教室关门了，%d个学生关在教室里了!", latch.getCount()));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        executor.execute(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            latch.countDown();
            System.out.println(String.format("走了一个学生，还剩%d个", latch.getCount()));
        });

        executor.execute(() -> {
            latch.countDown();
            System.out.println(String.format("又走了一个学生，还剩%d个", latch.getCount()));
        });

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    /**
     * 公平锁
     */
    @Test
    void fairLock() throws InterruptedException {
        RLock lock = redissonClient.getFairLock("test");

        int size = 10;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            final int j = i;
            Thread t = new Thread(() -> {
                lock.lock();
                lock.unlock();
            });

            threads.add(t);
        }

        for (Thread thread : threads) {
            thread.start();
            thread.join(5);
        }

        for (Thread thread : threads) {
            thread.join();
        }
    }

    /**
     * 读写锁
     */
    @Test
    void readWriteLock() throws InterruptedException {
        final RReadWriteLock lock = redissonClient.getReadWriteLock("lock");

        lock.writeLock().tryLock();

        Thread t = new Thread(() -> {
            RLock r = lock.readLock();
            r.lock();
            System.out.println("子线程获得读锁，开始执行...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            r.unlock();
        });

        t.start();

        System.out.println("等待子线程执行...");
        Thread.sleep(500);

        lock.writeLock().unlock();
        System.out.println("主线程写锁释放");

        t.join();

        redissonClient.shutdown();
    }

    /**
     * 联锁
     *
     * @throws InterruptedException
     */
    @Test
    void multiLock() throws InterruptedException {
        RLock lock1 = redissonClient.getLock("lock1");
        RLock lock2 = redissonClient.getLock("lock2");
        RLock lock3 = redissonClient.getLock("lock3");

        Thread t = new Thread() {
            public void run() {
                RedissonMultiLock lock = new RedissonMultiLock(lock1);
                lock.lock();
                System.out.println("子线程加锁成功");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                }

                System.out.println("子线程解锁成功");
                lock.unlock();
            }
        };
        t.start();
        t.join(1000);

        RedissonMultiLock lock = new RedissonMultiLock(lock1, lock2, lock3);
        System.out.println("主线程等待加锁...");
        lock.lock();
        System.out.println("主线程加锁成功");
        lock.unlock();
    }

    /**
     * 红锁
     *
     * @throws InterruptedException
     */
    @Test
    void redLock() throws InterruptedException {
        Config config1 = new Config();
        config1.useSingleServer().setAddress("redis://xx.xx.xx.xx:9865").setDatabase(0).setPassword("password").setTimeout(5000);

        Config config2 = new Config();
        config2.useSingleServer().setAddress("redis://xx.xx.xx.xx:9865").setDatabase(1).setPassword("password").setTimeout(5000);

        RedissonClient client1 = Redisson.create(config1);
        RedissonClient client2 = Redisson.create(config2);

        RLock lock1 = client1.getLock("lock1");
        RLock lock2 = client1.getLock("lock2");
        RLock lock3 = client2.getLock("lock3");

        Thread t1 = new Thread() {
            public void run() {
                lock3.lock();
                System.out.println("lock3加锁成功");
            }
        };
        t1.start();
        t1.join();

        Thread t = new Thread() {
            public void run() {
                RedissonMultiLock lock = new RedissonRedLock(lock1, lock2, lock3);
                lock.lock();
                System.out.println("子线程lock1,lock2,lock3加锁成功");

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                lock.unlock();
                System.out.println("子线程lock1,lock2,lock3解锁成功");
            }
        };
        t.start();
        t.join(2000);

        lock3.forceUnlock();
        System.out.println("lock3强制解锁");

        RedissonMultiLock lock = new RedissonRedLock(lock1, lock2, lock3);
        lock.lock();
        System.out.println("主线程lock1,lock2,lock3加锁成功");
        lock.unlock();
        System.out.println("主线程lock1,lock2,lock3解锁成功");

        client1.shutdown();
        client2.shutdown();
    }


    @Test
    void semaphore() throws InterruptedException {
        RSemaphore s = redissonClient.getSemaphore("test");
        s.trySetPermits(5);
        s.acquire(3);
        System.out.println("获得3个信号量，还剩2个");
        Thread t = new Thread() {
            @Override
            public void run() {
                RSemaphore s = redissonClient.getSemaphore("test");
                s.release();
                System.out.println("释放1个");
                s.release();
                System.out.println("又释放1个");
            }
        };

        t.start();

        s.acquire(4);
        System.out.println("再获得4个");

        redissonClient.shutdown();
    }
}
