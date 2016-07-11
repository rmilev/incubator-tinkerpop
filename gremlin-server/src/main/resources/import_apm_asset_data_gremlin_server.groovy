import org.apache.tinkerpop.gremlin.server.util.LifeCycleHook

assetFile1 = new File(this.getClass().getResource("/sample_data/apm/asset/BetaE2EAsset.json").toURI().getPath())
assetFile2 = new File(this.getClass().getResource("/sample_data/apm/asset/windAssetModel.json").toURI().getPath())
eventFiles1 = new File(this.getClass().getResource("/sample_data/apm/alerts/details").toURI().getPath())
caseFiles1 = new File(this.getClass().getResource("/sample_data/apm/cases/details").toURI().getPath())
rasgasAssetFile = new File(this.getClass().getResource("/sample_data/apm/asset/Rasgas_Company_Limited.txt").toURI().getPath())

baseScriptFile = new File(this.getClass().getResource("/import_apm_asset_data_base.groovy").toURI().getPath())

evaluate(baseScriptFile)

def globals = [:]

globals << [hook : [
        onStartUp: { ctx ->
            createModel(assetFile1, assetFile2, rasgasAssetFile, eventFiles1, caseFiles1)
        }
] as LifeCycleHook]

globals << [graph : graph]
globals << [g : g]