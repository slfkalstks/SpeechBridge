package kc.ac.uc.speechbridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AutoReadSyncTest {
    @Test fun preferenceSurvivesOfflineRestartAndIsRemovedFromOutboxAfterAck() = runTest {
        val secure=MemoryCredentialStore()
        val identity=SyncIdentity("https://example.test","synthetic-user")
        val remote=object:SyncRemote {
            var offline=false
            var saved=Archive(1,emptyList())
            var version=0
            override suspend fun snapshot(identity:SyncIdentity,deviceId:String):DataSnapshot {
                if(offline) error("offline")
                return DataSnapshot(saved,if(version==0) emptyMap() else mapOf("settings:settings" to version.toString()),version.toString())
            }
            override suspend fun mutate(identity:SyncIdentity,mutation:PendingMutation):MutationReceipt {
                if(offline) error("offline")
                val value=mutation.value!!.jsonObject
                saved=saved.copy(appearance=Appearance.valueOf(value.getValue("appearance").jsonPrimitive.content),
                    autoReadTranslation=value.getValue("autoReadTranslation").jsonPrimitive.boolean)
                version++
                return MutationReceipt("settings","settings",version.toString(),version.toString())
            }
        }
        val repo=SyncRepository(secure,remote);repo.open(identity)
        remote.offline=true;repo.save(Archive(1,emptyList(),autoReadTranslation=false))
        assertEquals(1,repo.pendingCount())
        val reopened=SyncRepository(secure,remote)
        assertFalse(reopened.open(identity).autoReadTranslation)
        remote.offline=false;reopened.flush();assertEquals(0,reopened.pendingCount())
        assertFalse(SyncRepository(secure,remote).open(identity).autoReadTranslation)
        assertFalse(secure.read()!!.contains("autoReadTranslation"))
    }
}
