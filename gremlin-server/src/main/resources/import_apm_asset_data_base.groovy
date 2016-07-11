import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.json.internal.LazyMap
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.io.IoCore
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph

import java.nio.ByteBuffer;

import static org.apache.tinkerpop.gremlin.structure.T.*;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;

import java.util.List;
import java.util.Map;
import groovy.json.JsonSlurper

import static org.apache.tinkerpop.gremlin.structure.io.IoCore.graphson

graph = TinkerGraph.open()
g = graph.traversal()

createVertexes = {businessModelName, canonicalModelName ->
    inputJSON.instances.findAll{it.ccomClass == businessModelName}.each{ instance ->
        def asset = graph.addVertex(label, canonicalModelName)
        instance.keySet().findAll{it != 'properties'}.each{key -> setPropertyValue(asset, key, instance.get(key)) }
        instance.properties?.forEach{prop -> prop.value.forEach{ value -> asset.property(prop.id, value) }}
    }
}

createEdges = {businessModelParentTypeName, canonicalModelRelationshipName, canonicalModelInverseRelationshipName ->
    inputJSON.connections.findAll{it.to[0].ccomClass == businessModelParentTypeName}.each{connection ->
        g.V().has('internal_id', connection.to[0].id).next().each{
            from = it
            to = g.V().has('internal_id', connection.from.id).next()
            if(canonicalModelRelationshipName) from.addEdge(canonicalModelRelationshipName, to)
            if(canonicalModelInverseRelationshipName) to.addEdge(canonicalModelInverseRelationshipName, from)
        }
    }
}

createAssetModel = {
    createVertexes('ENTERPRISE', 'Vendor')
    createVertexes('SITE', 'ThingLocation')
    createVertexes('SEGMENT', 'Asset')
    createVertexes('ASSET', 'Asset')

    createEdges('SEGMENT', 'child', 'parent')
    createEdges('ASSET', 'child', 'parent')
    createEdges('ENTERPRISE', null, 'provided-by')
    createEdges('SITE', null, 'current-location')
}

//quick and dirty mapping of the asset ID between the two data sets
mapAssetIds = {uuidFromAlarmData, nrAssets ->
    uuidBytes = uuidFromAlarmData.getBytes()
    ByteBuffer bb = ByteBuffer.wrap(new byte[32])
    bb.put(Arrays.copyOfRange(uuidBytes, 0, 16))
    bb.put(Arrays.copyOfRange(uuidBytes, 16, 32))
    return new BigInteger(bb.array()).mod(nrAssets)
}

createEventModel = {eventFiles1 ->
    eventFiles1.eachFile(FileType.FILES) {
        if (it.getName().endsWith(".json")) {
            def alertJson = new JsonSlurper().parseText(it.text)
            def assetId = alertJson.associatedMonitoredEntityUuid.replaceAll('/assets/', '').replaceAll('-', '').toUpperCase()
            def alert = graph.addVertex(label, 'ThingEvent')
            alertJson.keySet().findAll {
                it != 'alarmProfile'
            }.each { key -> setPropertyValue(alert, key, alertJson.get(key)) }
            if(alertJson?.alarmProfile?.id){
                def eventTemplate = g.V().has('ThingEventTemplate', 'internal_id', alertJson.alarmProfile.id+"").tryNext().orElseGet {
                    def newEventTemplate = graph.addVertex(label, 'ThingEventTemplate')
                    alertJson.alarmProfile.keySet().each { key -> setPropertyValue(newEventTemplate, key, alertJson.alarmProfile.get(key)) }
                    newEventTemplate
                }
                alert.addEdge('instance-of', eventTemplate)
            }
            alert.addEdge('is-for', assets[mapAssetIds(assetId, assets.size())])
        }
    }
}
setPropertyValue = { vertex, name, property ->
    if(name == 'id'){
        name = 'internal_id'
    }
    if (property) {
        propValue = property instanceof LazyMap ? JsonOutput.toJson(property) : property.toString();
        if(propValue instanceof ArrayList){
            //TODO
            propValue = null
        }
        else{
        }
    }
    else {
        propValue = null
    }
    if(propValue)
        vertex.property(name, propValue)

}


createCaseModel = {caseFiles1 ->
    caseFiles1.eachFile(FileType.FILES) {
        if (it.getName().endsWith(".json")) {
            def caseJson = new JsonSlurper().parseText(it.text)
            def assetId = caseJson.asset.uuid.replaceAll('/assets/', '').replaceAll('-', '').toUpperCase()
            def _case = graph.addVertex(label, 'Case')
            caseJson.keySet().each { key -> setPropertyValue(_case, key, caseJson.get(key) ) }
            _case.addEdge('is-for', assets[mapAssetIds(assetId, assets.size())])
            caseJson.alerts.each {
                if (it.uuid) {
                    def linkedEvent = g.V().has('ThingEvent', 'uuid', it.uuid).next()
                    _case.addEdge('linked-to', linkedEvent)
                }
            }

        }
    }
}

createRasgasAssetModel = { asset, parent, relationshipName, inverseRelationshipName ->
    def type = "Asset"
    relationshipName = "parent"
    inverseRelationshipName = "child"
    if(asset.Entity == 'Enterprise') {
        type = "Vendor"
        relationshipName = "provided-by"
        inverseRelationshipName = null
    }
    else if(asset.Entity == 'Site') {
        type = "ThingLocation"
        relationshipName = "current-location"
        inverseRelationshipName = null
    }

    def _asset = graph.addVertex(label, type)
    asset.keySet().findAll{it != 'Attributes' && it != 'Children'}.each{key -> setPropertyValue(_asset, key, asset.get(key))}
    asset.Attributes?.forEach{prop -> setPropertyValue(_asset, prop.Attribute, prop.Value)}
    if(parent){
        if(relationshipName)
            _asset.addEdge(relationshipName, parent)
        if(inverseRelationshipName)
            parent.addEdge(inverseRelationshipName, _asset)
    }

    asset.Children.each({createRasgasAssetModel(it, _asset, relationshipName, inverseRelationshipName)})
}


createModel = {assetFile1, assetFile2, rasgasAssetFile, eventFiles1, caseFiles1 ->
//    inputJSON = new JsonSlurper().parseText(assetFile1.text)
//    createAssetModel()
//    inputJSON = new JsonSlurper().parseText(assetFile2.text)
//    createAssetModel()
    rasgasJson = new JsonSlurper().parseText(rasgasAssetFile.text)
    createRasgasAssetModel(rasgasJson, null, null, null)

    assets = []
    g.V().has(label, 'Asset').each { assets << it }

    createEventModel(eventFiles1)
    createCaseModel(caseFiles1)

    //g.V().hasLabel('ThingEvent').filter({it.get().edges(Direction.IN, 'linked-to').size() == 0}).drop()
    //g.E().hasLabel('child').drop()

    a = graph.addVertex(label, 'Analytics', 'name', 'Vibration Anomaly Detection')
    v = g.V().has('Asset', 'Name', '296978').next()
    a.addEdge('applicable-for', v)


}

//mapper = graph.io(graphson()).mapper().embedTypes(true).create()
//graph.io(graphson()).writer().mapper(mapper).create().writeGraph(new FileOutputStream("../graph.json"), graph);

////all Locations in the Graph
//g.V().has(label, 'ThingLocation').values('name')
////Asset with certain plate nr
//g.V().has(label, 'ThingLocation').has('name','Chinook').out('current-location').has('plate_number', 'W77386')
//// Asset with filtering on property
//g.V().has(label, 'ThingLocation').has('name','Chinook').out('current-location').filter{it.get().property('elevation').isPresent() && it.get().property('elevation').value()?.toInteger() > 1076}
////Assets by location
//g.V().has(label, 'ThingLocation').has('name','Chinook').as('location').out('current-location').has(label, 'Asset').as('asset').select('location','asset').by('id')
