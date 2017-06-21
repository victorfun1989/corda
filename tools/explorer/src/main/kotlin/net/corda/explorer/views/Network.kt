package net.corda.explorer.views

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.animation.FadeTransition
import javafx.animation.TranslateTransition
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.util.Duration
import net.corda.client.jfx.model.*
import net.corda.client.jfx.utils.*
import net.corda.core.contracts.ContractState
import net.corda.core.identity.Party
import net.corda.core.crypto.toBase58String
import net.corda.core.node.NodeInfo
import net.corda.explorer.formatters.PartyNameFormatter
import net.corda.explorer.model.CordaView
import tornadofx.*

class Network : CordaView() {
    override val root by fxml<Parent>()
    override val icon = FontAwesomeIcon.GLOBE
    // Inject data.
    val myIdentity by observableValue(NetworkIdentityModel::myIdentity)
    val notaries by observableList(NetworkIdentityModel::notaries)
    val peers by observableList(NetworkIdentityModel::parties)
    val transactions by observableList(TransactionDataModel::partiallyResolvedTransactions)
    // UI components
    private val myIdentityPane by fxid<BorderPane>()
    private val notaryList by fxid<VBox>()
    private val peerList by fxid<VBox>()
    private val mapScrollPane by fxid<ScrollPane>()
    private val mapPane by fxid<Pane>()
    private val mapImageView by fxid<ImageView>()
    private val zoomInButton by fxid<Button>()
    private val zoomOutButton by fxid<Button>()

    private val mapOriginalHeight = 2000.0

    // UI node observables, declare here to create a strong ref to prevent GC, which removes listener from observables.
    private var centralLabel: Label? = null
    private val notaryComponents = notaries.map { it.render() }
    private val notaryButtons = notaryComponents.map { it.button }
    private val peerComponents = peers.map { it.render() }
    private val peerButtons = peerComponents.filtered { it.nodeInfo != myIdentity.value }.map { it.button }
    private val allComponents = FXCollections.observableArrayList(notaryComponents, peerComponents).concatenate()
    private val allComponentMap = allComponents.associateBy { it.nodeInfo.legalIdentity }

    private data class MapViewComponents(val nodeInfo: NodeInfo, val button: Button, val label: Label)

    private val stepDuration = Duration.millis(500.0)

    private val lastTransactions = transactions.last().map {
        it?.let {
            val inputParties = it.inputs.sequence()
                    .map { it as? PartiallyResolvedTransaction.InputResolution.Resolved }
                    .filterNotNull()
                    .map { it.stateAndRef.state.data }.getParties()
            val outputParties = it.transaction.tx.outputs.map { it.data }.observable().getParties()
            val signingParties = it.transaction.sigs.map { getModel<NetworkIdentityModel>().lookup(it.by) }
            // Input parties fire a bullets to all output parties, and to the signing parties. !! This is a rough guess of how the message moves in the network.
            // TODO : Expose artemis queue to get real message information.
            inputParties.cross(outputParties) + inputParties.cross(signingParties)
        }
    }

    private fun NodeInfo.renderButton(mapLabel: Label): Button {
        val node = this
        return button {
            useMaxWidth = true
            graphic = vbox {
                label(PartyNameFormatter.short.format(node.legalIdentity.name)) { font = Font.font(font.family, FontWeight.BOLD, 15.0) }
                gridpane {
                    hgap = 5.0
                    vgap = 5.0
                    row("Pub Key :") {
                        copyableLabel(SimpleObjectProperty(node.legalIdentity.owningKey.toBase58String())).apply { minWidth = 400.0 }
                    }
                    row("Services :") { label(node.advertisedServices.map { it.info }.joinToString(", ")) }
                    node.worldMapLocation?.apply { row("Location :") { label(this@apply.description) } }
                }
            }
            setOnMouseClicked {
                centralLabel = mapLabel
                mapScrollPane.centerLabel(mapLabel)
            }
        }
    }

    private fun NodeInfo.render(): MapViewComponents {
        val node = this
        val mapLabel = label(PartyNameFormatter.short.format(node.legalIdentity.name))
        mapPane.add(mapLabel)
        // applyCss: This method does not normally need to be invoked directly but may be used in conjunction with Parent.layout()
        // to size a Node before the next pulse, or if the Scene is not in a Stage.
        // It's needed to properly add node label to the map (before that width and height are 0 which results in wrong placement of
        // nodes rendered after initial map rendering).
        mapPane.applyCss()
        mapPane.layout()
        mapLabel.apply {
            graphic = FontAwesomeIconView(FontAwesomeIcon.DOT_CIRCLE_ALT)
            contentDisplay = ContentDisplay.TOP
            val coordinate = Bindings.createObjectBinding({
                // These coordinates are obtained when we generate the map using TileMill.
                node.worldMapLocation?.coordinate?.project(mapPane.width, mapPane.height, 85.0511, -85.0511, -180.0, 180.0) ?: Pair(0.0, 0.0)
            }, arrayOf(mapPane.widthProperty(), mapPane.heightProperty()))
            // Center point of the label.
            layoutXProperty().bind(coordinate.map { it.first - width / 2 })
            layoutYProperty().bind(coordinate.map { it.second - height / 4 })
        }

        val button = node.renderButton(mapLabel)
        if (node == myIdentity.value) {
            // It has to be a copy if we want to have notary both in notaries list and in identity (if we are looking at that particular notary node).
            myIdentityPane.apply { center = node.renderButton(mapLabel) }
            centralLabel = mapLabel
        }
        return MapViewComponents(this, button, mapLabel)
    }

    init {
        Bindings.bindContent(notaryList.children, notaryButtons)
        Bindings.bindContent(peerList.children, peerButtons)
        // Run once when the screen is ready.
        // TODO : Find a better way to do this.
        mapPane.heightProperty().addListener { _, old, _ ->
            if (old == 0.0) centralLabel?.let {
                mapPane.applyCss()
                mapPane.layout()
                mapScrollPane.centerLabel(it)
            }
        }

        // Listen on zooming gesture, if device has gesture support.
        mapPane.setOnZoom { zoom(it.zoomFactor, Point2D(it.x, it.y)) }

        // Zoom controls for the map.
        zoomInButton.setOnAction { zoom(1.2) }
        zoomOutButton.setOnAction { zoom(0.8) }

        lastTransactions.addListener { _, _, new ->
            new?.forEach {
                it.first.value?.let { a ->
                    it.second.value?.let { b ->
                        fireBulletBetweenNodes(a.legalIdentity, b.legalIdentity, "bank", "bank")
                    }
                }
            }
        }
    }

    private fun ScrollPane.centerLabel(label: Label) {
        this.hvalue = (label.boundsInParent.width / 2 + label.boundsInParent.minX) / mapImageView.layoutBounds.width
        this.vvalue = (label.boundsInParent.height / 2 + label.boundsInParent.minY) / mapImageView.layoutBounds.height
    }

    private fun zoom(zoomFactor: Double, mousePoint: Point2D = mapScrollPane.viewportBounds.center()) {
        // Work out scroll bar position.
        val valX = mapScrollPane.hvalue * (mapImageView.layoutBounds.width - mapScrollPane.viewportBounds.width)
        val valY = mapScrollPane.vvalue * (mapImageView.layoutBounds.height - mapScrollPane.viewportBounds.height)
        // Set zoom scale limit to minimum 1x and maximum 10x.
        val newHeight = Math.min(Math.max(mapImageView.prefHeight(-1.0) * zoomFactor, mapOriginalHeight), mapOriginalHeight * 10)
        val newZoomFactor = newHeight / mapImageView.prefHeight(-1.0)
        // calculate adjustment of scroll position based on mouse location.
        val adjustment = mousePoint.multiply(newZoomFactor - 1)
        // Change the map size.
        mapImageView.fitHeight = newHeight
        mapScrollPane.layout()
        // Adjust scroll.
        mapScrollPane.hvalue = (valX + adjustment.x) / (mapImageView.layoutBounds.width - mapScrollPane.viewportBounds.width)
        mapScrollPane.vvalue = (valY + adjustment.y) / (mapImageView.layoutBounds.height - mapScrollPane.viewportBounds.height)
    }

    private fun Bounds.center(): Point2D {
        val x = this.width / 2 - this.minX
        val y = this.height / 2 - this.minY
        return Point2D(x, y)
    }

    private fun List<ContractState>.getParties() = map { it.participants.map { getModel<NetworkIdentityModel>().lookup(it.owningKey) } }.flatten()

    private fun fireBulletBetweenNodes(senderNode: Party, destNode: Party, startType: String, endType: String) {
        allComponentMap[senderNode]?.let { senderNode ->
            allComponentMap[destNode]?.let { destNode ->
                val sender = senderNode.label.boundsInParentProperty().map { Point2D(it.width / 2 + it.minX, it.height / 4 - 2.5 + it.minY) }
                val receiver = destNode.label.boundsInParentProperty().map { Point2D(it.width / 2 + it.minX, it.height / 4 - 2.5 + it.minY) }
                val bullet = Circle(3.0)
                bullet.styleClass += "bullet"
                bullet.styleClass += "connection-$startType-to-$endType"
                with(TranslateTransition(stepDuration, bullet)) {
                    fromXProperty().bind(sender.map { it.x })
                    fromYProperty().bind(sender.map { it.y })
                    toXProperty().bind(receiver.map { it.x })
                    toYProperty().bind(receiver.map { it.y })
                    setOnFinished { mapPane.children.remove(bullet) }
                    play()
                }
                val line = Line().apply {
                    styleClass += "message-line"
                    startXProperty().bind(sender.map { it.x })
                    startYProperty().bind(sender.map { it.y })
                    endXProperty().bind(receiver.map { it.x })
                    endYProperty().bind(receiver.map { it.y })
                }
                // Fade in quick, then fade out slow.
                with(FadeTransition(stepDuration.divide(5.0), line)) {
                    fromValue = 0.0
                    toValue = 1.0
                    play()
                    setOnFinished {
                        with(FadeTransition(stepDuration.multiply(6.0), line)) {
                            fromValue = 1.0
                            toValue = 0.0
                            play()
                            setOnFinished { mapPane.children.remove(line) }
                        }
                    }
                }
                mapPane.children.add(1, line)
                mapPane.children.add(bullet)
            }
        }
    }
}
