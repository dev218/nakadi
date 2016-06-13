package de.zalando.aruha.nakadi.service.subscription.zk;

import de.zalando.aruha.nakadi.service.subscription.SubscriptionWrappedException;
import de.zalando.aruha.nakadi.service.subscription.model.Partition;
import de.zalando.aruha.nakadi.service.subscription.model.Session;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CuratorZkSubscriptionClient implements ZkSubscriptionClient {
    private static final String STATE_INITIALIZED = "INITIALIZED";
    private static final Charset CHARSET = Charset.forName("UTF-8");

    private final CuratorFramework curatorFramework;
    private final InterProcessSemaphoreMutex lock;
    private final String subscriptionId;
    private final Logger log;

    public CuratorZkSubscriptionClient(final String subscriptionId, final CuratorFramework curatorFramework) {
        this.subscriptionId = subscriptionId;
        this.curatorFramework = curatorFramework;
        this.lock = new InterProcessSemaphoreMutex(curatorFramework, "/nakadi/locks/subscription_" + subscriptionId);
        this.log = LoggerFactory.getLogger("zk." + subscriptionId);
    }

    @Override
    public void runLocked(final Runnable function) {
        log.info("Taking lock for " + function.hashCode());
        try {
            lock.acquire();
            log.debug("Lock taken " + function.hashCode());
            try {
                function.run();
            } finally {
                log.info("Releasing lock for " + function.hashCode());
                try {
                    lock.release();
                } catch (final Exception e) {
                    log.error("Failed to release lock", e);
                    throw e;
                }
                log.debug("Lock released " + function.hashCode());
            }
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    private String getSubscriptionPath(final String value) {
        return "/nakadi/subscriptions/" + subscriptionId + value;
    }

    private String getPartitionPath(final Partition.PartitionKey key) {
        return getSubscriptionPath("/topics/" + key.topic + "/" + key.partition);
    }

    @Override
    public boolean createSubscription() {
        try {
            final String statePath = getSubscriptionPath("/state");
            final Stat stat = curatorFramework.checkExists().forPath(statePath);
            if (null == stat) {
                // node not exists.
                curatorFramework.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(statePath);
                return true;
            } else {
                final String state = new String(curatorFramework.getData().forPath(statePath), CHARSET);
                return !state.equals(STATE_INITIALIZED);
            }
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public void fillEmptySubscription(final Map<Partition.PartitionKey, Long> partitionToOffset) {
        try {
            log.info("Creating sessions root");
            if (null != curatorFramework.checkExists().forPath(getSubscriptionPath("/sessions"))) {
                curatorFramework.delete().guaranteed().deletingChildrenIfNeeded().forPath(getSubscriptionPath("/sessions"));
            }
            curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(getSubscriptionPath("/sessions"));

            log.info("Creating topics");
            if (null != curatorFramework.checkExists().forPath(getSubscriptionPath("/topics"))) {
                log.info("deleting topics recursively");
                curatorFramework.delete().guaranteed().deletingChildrenIfNeeded().forPath(getSubscriptionPath("/topics"));
            }
            curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(getSubscriptionPath("/topics"));

            log.info("Creating partitions");
            int counter = 0;
            for (final Map.Entry<Partition.PartitionKey, Long> p : partitionToOffset.entrySet()) {
                final String partitionPath = getPartitionPath(p.getKey());
                curatorFramework.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(
                        partitionPath,
                        serializeNode(null, null, Partition.State.UNASSIGNED));
                curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(
                        partitionPath + "/offset",
                        (String.valueOf(p.getValue())).getBytes(CHARSET));
                if (++counter % 100 == 0) {
                    log.info("Created " + counter + " so far");
                }
            }
            log.info("creating topology node");
            curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(getSubscriptionPath("/topology"), "0".getBytes(CHARSET));
            log.info("updating state");
            curatorFramework.setData().forPath(getSubscriptionPath("/state"), STATE_INITIALIZED.getBytes(CHARSET));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    private static byte[] serializeNode(final String session, final String nextSession, final Partition.State state) {
        return Stream.of(
                session == null ? "" : session,
                nextSession == null ? "" : nextSession,
                state.name()).collect(Collectors.joining(":")).getBytes(CHARSET);
    }

    private static Partition deserializeNode(final Partition.PartitionKey key, final byte[] data) {
        final String[] parts = new String(data, CHARSET).split(":");
        return new Partition(
                key,
                parts[0].isEmpty() ? null : parts[0],
                parts[1].isEmpty() ? null : parts[1],
                Partition.State.valueOf(parts[2]));
    }

    @Override
    public void updatePartitionConfiguration(final Partition partition) {
        try {
            log.info("updating partition state: " + partition.getKey() + ":" + partition.getState() + ":" + partition.getSession() + ":" + partition.getNextSession());
            curatorFramework.setData().forPath(
                    getPartitionPath(partition.getKey()),
                    serializeNode(partition.getSession(), partition.getNextSession(), partition.getState()));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public void incrementTopology() {
        try {
            log.info("Incrementing topology version");
            final Integer curVersion = Integer.parseInt(new String(curatorFramework.getData().forPath(getSubscriptionPath("/topology")), CHARSET));
            curatorFramework.setData().forPath(getSubscriptionPath("/topology"), String.valueOf(curVersion + 1).getBytes(CHARSET));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public Partition[] listPartitions() {
        log.info("fetching partitions information");
        try {
            final List<Partition> partitions = new ArrayList<>();
            for (final String topic : curatorFramework.getChildren().forPath(getSubscriptionPath("/topics"))) {
                for (final String partition : curatorFramework.getChildren().forPath(getSubscriptionPath("/topics/" + topic))) {
                    final Partition.PartitionKey key = new Partition.PartitionKey(topic, partition);
                    partitions.add(deserializeNode(key, curatorFramework.getData().forPath(getPartitionPath(key))));
                }
            }
            return partitions.toArray(new Partition[partitions.size()]);
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public Session[] listSessions() {
        log.info("fetching sessions information");
        final List<Session> sessions = new ArrayList<>();
        try {
            for (final String sessionId : curatorFramework.getChildren().forPath(getSubscriptionPath("/sessions"))) {
                final int weight = Integer.parseInt(new String(curatorFramework.getData().forPath(getSubscriptionPath("/sessions/" + sessionId)), CHARSET));
                sessions.add(new Session(sessionId, weight));
            }
            return sessions.toArray(new Session[sessions.size()]);
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public ZKSubscription subscribeForSessionListChanges(final Runnable listener) {
        log.info("subscribeForSessionListChanges: " + listener.hashCode());
        return ChangeListener.forChildren(curatorFramework, getSubscriptionPath("/sessions"), listener);
    }

    @Override
    public ZKSubscription subscribeForTopologyChanges(final Runnable onTopologyChanged) {
        log.info("subscribeForTopologyChanges");
        return ChangeListener.forData(curatorFramework, getSubscriptionPath("/topology"), onTopologyChanged);
    }

    @Override
    public ZKSubscription subscribeForOffsetChanges(final Partition.PartitionKey key, final Runnable commitListener) {
        log.info("subscribeForOffsetChanges");
        return ChangeListener.forData(curatorFramework, getPartitionPath(key) + "/offset", commitListener);
    }

    @Override
    public long getOffset(final Partition.PartitionKey key) {
        try {
            return Long.parseLong(new String(curatorFramework.getData().forPath(getPartitionPath(key) + "/offset"), CHARSET));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public void registerSession(final Session session) {
        log.info("Registering session " + session);
        try {
            final String clientPath = getSubscriptionPath("/sessions/" + session.getId());
            curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(clientPath, String.valueOf(session.getWeight()).getBytes(CHARSET));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public void unregisterSession(final Session session) {
        try {
            curatorFramework.delete().guaranteed().forPath(getSubscriptionPath("/sessions/" + session.getId()));
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }

    @Override
    public void transfer(final String sessionId, final Collection<Partition.PartitionKey> partitions) {
        log.info("session " + sessionId + " releases partitions " + partitions);
        boolean changed = false;
        try {
            for (final Partition.PartitionKey pk : partitions) {
                final Partition realPartition = deserializeNode(
                        pk,
                        curatorFramework.getData().forPath(getPartitionPath(pk)));
                if (sessionId.equals(realPartition.getSession()) && realPartition.getState() == Partition.State.REASSIGNING) {
                    updatePartitionConfiguration(realPartition.toState(Partition.State.ASSIGNED, realPartition.getNextSession(), null));
                    changed = true;
                }
            }
            if (changed) {
                incrementTopology();
            }
        } catch (final Exception e) {
            throw new SubscriptionWrappedException(e);
        }
    }
}
