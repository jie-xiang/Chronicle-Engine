package net.openhft.chronicle.engine;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapView;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.cfg.EngineCfg;
import net.openhft.chronicle.engine.cfg.Installable;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.engine.fs.EngineCluster;
import net.openhft.chronicle.engine.fs.EngineHostDetails;
import net.openhft.chronicle.engine.map.CMap2EngineReplicator;
import net.openhft.chronicle.engine.map.ChronicleMapKeyValueStore;
import net.openhft.chronicle.engine.map.VanillaMapView;
import net.openhft.chronicle.engine.query.QueueConfig;
import net.openhft.chronicle.engine.server.ServerEndpoint;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.engine.tree.TopologicalEvent;
import net.openhft.chronicle.engine.tree.VanillaAssetTree;
import net.openhft.chronicle.network.NetworkStats;
import net.openhft.chronicle.network.NetworkStatsListener;
import net.openhft.chronicle.network.cluster.HostDetails;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;

import static net.openhft.chronicle.core.onoes.PrintExceptionHandler.WARN;
import static net.openhft.chronicle.engine.api.tree.RequestContext.loadDefaultAliases;

/**
 * @author Rob Austin.
 */
public class EngineInstance {

    static final Logger LOGGER = LoggerFactory.getLogger(EngineInstance.class);

    static {
        try {
            net.openhft.chronicle.core.Jvm.setExceptionHandlers(WARN, WARN, null);
            loadDefaultAliases();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static VanillaAssetTree engineMain(final int hostId, final String yamlConfigFile, String cluster) throws IOException {
        return engineMain(hostId, yamlConfigFile, cluster, "");
    }


    public static VanillaAssetTree engineMain(final int hostId, final String yamlConfigFile, String cluster, String region) {
        try {

            @NotNull final VanillaAssetTree tree = createAssetTree(yamlConfigFile, region, hostId,cluster);

            @Nullable final Clusters clusters = tree.root().getView(Clusters.class);

            if (clusters == null || clusters.size() == 0) {
                Jvm.warn().on(EngineInstance.class, "cluster not found");
                return null;
            }

            setUpEndpoint(hostId, cluster, tree);
            return tree;

        } catch (Exception e) {
            throw Jvm.rethrow(e);
        }
    }

    @NotNull
    public static VanillaAssetTree createAssetTree(String yamlConfigFile, String region, int hostId,String clusterName) throws IOException {
        @NotNull TextWire yaml = TextWire.fromFile(yamlConfigFile);

        @NotNull EngineCfg installable = (EngineCfg) yaml.readObject();

        @NotNull final VanillaAssetTree tree = new VanillaAssetTree(hostId).forServer(false);
        tree.region(region);
        tree.clusterName(clusterName);

        @NotNull final Asset connectivityMap = tree.acquireAsset("/proc/connections/cluster/connectivity");
        connectivityMap.addWrappingRule(MapView.class, "map directly to KeyValueStore",
                VanillaMapView::new,
                KeyValueStore.class);
        connectivityMap.addLeafRule(EngineReplication.class, "Engine replication holder",
                CMap2EngineReplicator::new);
        connectivityMap.addLeafRule(KeyValueStore.class, "KVS is Chronicle Map", (context, asset) ->
                new ChronicleMapKeyValueStore(context.cluster(clusterName), asset));

        try {
            installable.install("/", tree);
            LOGGER.info("Engine started");
        } catch (Exception e) {
            LOGGER.error("Error starting a component, stopping", e);
            tree.close();
        }
        return tree;
    }




    public static void setUpEndpoint(int hostId, String cluster, VanillaAssetTree tree) throws IOException {

        tree.root().addView(HostIdentifier.class, new HostIdentifier((byte) hostId));


        @Nullable final Clusters clusters = tree.root().getView(Clusters.class);

        if (clusters == null || clusters.size() == 0) {
            throw new IllegalStateException("no clusters were found");
        }

        final EngineCluster engineCluster = clusters.get(cluster);

        if (engineCluster == null) {
            throw new IllegalStateException("cluster=" + cluster + " not found");
        }

        final HostDetails hostDetails = engineCluster.findHostDetails(hostId);

        final String connectUri = hostDetails.connectUri();
        engineCluster.clusterContext().assetRoot(tree.root());

        final NetworkStatsListener networkStatsListener = engineCluster.clusterContext()
                .networkStatsListenerFactory()
                .apply(engineCluster.clusterContext());

        @NotNull final ServerEndpoint serverEndpoint = new ServerEndpoint(connectUri, tree, networkStatsListener, cluster);

        // we add this as close will get called when the asset tree is closed
        tree.root().addView(ServerEndpoint.class, serverEndpoint);
        tree.registerSubscriber("", TopologicalEvent.class, e -> LOGGER.info("Tree change " + e));

        // the reason that we have to do this is to ensure that the network stats are
        // replicated between all hosts, if you don't acquire a queue it wont exist and so
        // will not act as a slave in replication
        for (@NotNull EngineHostDetails engineHostDetails : engineCluster.hostDetails()) {

            final int id = engineHostDetails
                    .hostId();
            Asset asset = tree.acquireAsset("/proc/connections/cluster/throughput/" + id);

            // sets the master of each of the queues
            asset.addView(new QueueConfig(x -> id, false, null, WireType.BINARY));

            tree.acquireQueue("/proc/connections/cluster/throughput/" + id,
                    String.class,
                    NetworkStats.class, engineCluster.clusterName());
        }
    }

    public static VanillaAssetTree engineMain(final int hostId, final String yamlConfigFile) {
        try {

            @NotNull TextWire yaml = TextWire.fromFile(yamlConfigFile);

            @NotNull EngineCfg installable = (EngineCfg) yaml.readObject();

            @NotNull final VanillaAssetTree tree = new VanillaAssetTree(hostId).forServer(false);

            @NotNull final Asset connectivityMap = tree.acquireAsset("/proc/connections/cluster/connectivity");
            connectivityMap.addWrappingRule(MapView.class, "map directly to KeyValueStore",
                    VanillaMapView::new,
                    KeyValueStore.class);
            connectivityMap.addLeafRule(EngineReplication.class, "Engine replication holder",
                    CMap2EngineReplicator::new);
            connectivityMap.addLeafRule(KeyValueStore.class, "KVS is Chronicle Map", (context, asset) ->
                    new ChronicleMapKeyValueStore(context.cluster(firstClusterName(tree)), asset));

            try {
                installable.install("/", tree);
                LOGGER.info("Engine started");
            } catch (Exception e) {
                LOGGER.error("Error starting a component, stopping", e);
                tree.close();
            }

            @Nullable final Clusters clusters = tree.root().getView(Clusters.class);

            if (clusters == null || clusters.size() == 0) {
                Jvm.warn().on(EngineInstance.class, "cluster not found");
                return null;
            }
            if (clusters.size() != 1) {
                Jvm.warn().on(EngineInstance.class, "unambiguous cluster, you have " + clusters.size() + "" +
                        " clusters which one do you want to use?");
                return tree;
            }

            final EngineCluster engineCluster = clusters.firstCluster();
            final HostDetails hostDetails = engineCluster.findHostDetails(hostId);
            final String connectUri = hostDetails.connectUri();
            engineCluster.clusterContext().assetRoot(tree.root());

            final NetworkStatsListener networkStatsListener = engineCluster.clusterContext()
                    .networkStatsListenerFactory()
                    .apply(engineCluster.clusterContext());

            @NotNull final ServerEndpoint serverEndpoint = new ServerEndpoint(connectUri, tree, networkStatsListener, engineCluster.clusterName());

            // we add this as close will get called when the asset tree is closed
            tree.root().addView(ServerEndpoint.class, serverEndpoint);
            tree.registerSubscriber("", TopologicalEvent.class, e -> LOGGER.info("Tree change " + e));

            // the reason that we have to do this is to ensure that the network stats are
            // replicated between all hosts, if you don't acquire a queue it wont exist and so
            // will not act as a slave in replication
            for (@NotNull EngineHostDetails engineHostDetails : engineCluster.hostDetails()) {

                final int id = engineHostDetails
                        .hostId();
                Asset asset = tree.acquireAsset("/proc/connections/cluster/throughput/" + id);

                // sets the master of each of the queues
                asset.addView(new QueueConfig(x -> id, false, null, WireType.BINARY));

                tree.acquireQueue("/proc/connections/cluster/throughput/" + id,
                        String.class,
                        NetworkStats.class, engineCluster.clusterName());
            }

            return tree;
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        }

    }

    /**
     * @return the first cluster name
     */
    static String firstClusterName(@NotNull VanillaAssetTree tree) {
        @Nullable final Clusters clusters = tree.root().getView(Clusters.class);
        if (clusters == null)
            return "";
        final EngineCluster engineCluster = clusters.firstCluster();
        if (engineCluster == null)
            return "";
        return engineCluster.clusterName();
    }


}
