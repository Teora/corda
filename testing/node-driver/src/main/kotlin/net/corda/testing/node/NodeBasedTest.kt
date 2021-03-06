package net.corda.testing.node

import net.corda.core.concurrent.CordaFuture
import net.corda.core.internal.concurrent.*
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.ServiceType
import net.corda.core.utilities.*
import net.corda.node.internal.Node
import net.corda.node.services.config.ConfigHelper
import net.corda.node.services.config.FullNodeConfiguration
import net.corda.node.services.config.configOf
import net.corda.node.services.config.plus
import net.corda.node.services.network.NetworkMapService
import net.corda.node.services.transactions.RaftValidatingNotaryService
import net.corda.node.utilities.ServiceIdentityGenerator
import net.corda.nodeapi.User
import net.corda.nodeapi.config.parseAs
import net.corda.testing.DUMMY_MAP
import net.corda.testing.TestDependencyInjectionBase
import net.corda.testing.driver.addressMustNotBeBoundFuture
import net.corda.testing.getFreeLocalPorts
import net.corda.testing.node.MockServices.Companion.MOCK_VERSION_INFO
import org.apache.logging.log4j.Level
import org.bouncycastle.asn1.x500.X500Name
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.*
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Extend this class if you need to run nodes in a test. You could use the driver DSL but it's extremely slow for testing
 * purposes. Use the driver if you need to run the nodes in separate processes otherwise this class will suffice.
 */
// TODO Some of the logic here duplicates what's in the driver
abstract class NodeBasedTest : TestDependencyInjectionBase() {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val nodes = ArrayList<Node>()
    private var _networkMapNode: Node? = null

    val networkMapNode: Node get() = _networkMapNode ?: startNetworkMapNode()

    init {
        System.setProperty("consoleLogLevel", Level.DEBUG.name().toLowerCase())
    }

    /**
     * Stops the network map node and all the nodes started by [startNode]. This is called automatically after each test
     * but can also be called manually within a test.
     */
    @After
    fun stopAllNodes() {
        val shutdownExecutor = Executors.newScheduledThreadPool(nodes.size)
        nodes.map { shutdownExecutor.fork(it::stop) }.transpose().getOrThrow()
        // Wait until ports are released
        val portNotBoundChecks = nodes.flatMap {
            listOf(
                    it.configuration.p2pAddress.let { addressMustNotBeBoundFuture(shutdownExecutor, it) },
                    it.configuration.rpcAddress?.let { addressMustNotBeBoundFuture(shutdownExecutor, it) }
            )
        }.filterNotNull()
        nodes.clear()
        _networkMapNode = null
        portNotBoundChecks.transpose().getOrThrow()
    }

    /**
     *  Clear network map data from nodes' databases.
     */
    fun clearAllNodeInfoDb() {
        nodes.forEach { it.services.networkMapCache.clearNetworkMapCache() }
    }

    /**
     * You can use this method to start the network map node in a more customised manner. Otherwise it
     * will automatically be started with the default parameters.
     */
    fun startNetworkMapNode(legalName: X500Name = DUMMY_MAP.name,
                            platformVersion: Int = 1,
                            advertisedServices: Set<ServiceInfo> = emptySet(),
                            rpcUsers: List<User> = emptyList(),
                            configOverrides: Map<String, Any> = emptyMap()): Node {
        check(_networkMapNode == null || _networkMapNode!!.info.legalIdentity.name == legalName)
        return startNodeInternal(legalName, platformVersion, advertisedServices + ServiceInfo(NetworkMapService.type), rpcUsers, configOverrides).apply {
            _networkMapNode = this
        }
    }

    @JvmOverloads
    fun startNode(legalName: X500Name,
                  platformVersion: Int = 1,
                  advertisedServices: Set<ServiceInfo> = emptySet(),
                  rpcUsers: List<User> = emptyList(),
                  configOverrides: Map<String, Any> = emptyMap(),
                  noNetworkMap: Boolean = false,
                  waitForConnection: Boolean = true): CordaFuture<Node> {
        val networkMapConf = if (noNetworkMap) {
            // Nonexistent network map service address.
            mapOf(
                    "networkMapService" to mapOf(
                            "address" to "localhost:10000",
                            "legalName" to networkMapNode.info.legalIdentity.name.toString()
                    )
            )
        } else {
            mapOf(
                    "networkMapService" to mapOf(
                            "address" to networkMapNode.configuration.p2pAddress.toString(),
                            "legalName" to networkMapNode.info.legalIdentity.name.toString()
                    )
            )
        }
        val node = startNodeInternal(
                legalName,
                platformVersion,
                advertisedServices,
                rpcUsers,
                networkMapConf + configOverrides,
                noNetworkMap)
        return if (waitForConnection) node.nodeReadyFuture.map { node } else doneFuture(node)
    }

    fun startNotaryCluster(notaryName: X500Name,
                           clusterSize: Int,
                           serviceType: ServiceType = RaftValidatingNotaryService.type): CordaFuture<List<Node>> {
        ServiceIdentityGenerator.generateToDisk(
                (0 until clusterSize).map { baseDirectory(getX500Name(O = "${notaryName.organisation}-$it", L = notaryName.locality, C = notaryName.country)) },
                serviceType.id,
                notaryName)

        val serviceInfo = ServiceInfo(serviceType, notaryName)
        val nodeAddresses = getFreeLocalPorts("localhost", clusterSize).map { it.toString() }

        val masterNodeFuture = startNode(
                getX500Name(O = "${notaryName.organisation}-0", L = notaryName.locality, C = notaryName.country),
                advertisedServices = setOf(serviceInfo),
                configOverrides = mapOf("notaryNodeAddress" to nodeAddresses[0],
                        "database" to mapOf("serverNameTablePrefix" to if (clusterSize > 1) "${notaryName.organisation}0".replace(Regex("[^0-9A-Za-z]+"), "") else "")))

        val remainingNodesFutures = (1 until clusterSize).map {
            startNode(
                    getX500Name(O = "${notaryName.organisation}-$it", L = notaryName.locality, C = notaryName.country),
                    advertisedServices = setOf(serviceInfo),
                    configOverrides = mapOf(
                            "notaryNodeAddress" to nodeAddresses[it],
                            "notaryClusterAddresses" to listOf(nodeAddresses[0]),
                            "database" to mapOf("serverNameTablePrefix" to "${notaryName.organisation}$it".replace(Regex("[^0-9A-Za-z]+"), ""))))
        }

        return remainingNodesFutures.transpose().flatMap { remainingNodes ->
            masterNodeFuture.map { masterNode -> listOf(masterNode) + remainingNodes }
        }
    }

    protected fun baseDirectory(legalName: X500Name) = tempFolder.root.toPath() / legalName.organisation.replace(WHITESPACE, "")

    private fun startNodeInternal(legalName: X500Name,
                                  platformVersion: Int,
                                  advertisedServices: Set<ServiceInfo>,
                                  rpcUsers: List<User>,
                                  configOverrides: Map<String, Any>,
                                  noNetworkMap: Boolean = false): Node {
        val baseDirectory = baseDirectory(legalName).createDirectories()
        val localPort = getFreeLocalPorts("localhost", 2)
        val p2pAddress = configOverrides["p2pAddress"] ?: localPort[0].toString()
        val config = ConfigHelper.loadConfig(
                baseDirectory = baseDirectory,
                allowMissingConfig = true,
                configOverrides = configOf(
                        "myLegalName" to legalName.toString(),
                        "p2pAddress" to p2pAddress,
                        "rpcAddress" to localPort[1].toString(),
                        "extraAdvertisedServiceIds" to advertisedServices.map { it.toString() },
                        "rpcUsers" to rpcUsers.map { it.toMap() },
                        "noNetworkMap" to noNetworkMap
                ) + configOverrides
        )

        val parsedConfig = config.parseAs<FullNodeConfiguration>()
        val node = Node(parsedConfig, parsedConfig.calculateServices(), MOCK_VERSION_INFO.copy(platformVersion = platformVersion),
                initialiseSerialization = false)
        node.start()
        nodes += node
        thread(name = legalName.organisation) {
            node.run()
        }
        return node
    }
}
