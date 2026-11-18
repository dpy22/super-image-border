package com.parker.imageborder.editor

import org.json.JSONObject

/** Only editor records are removed. Assets, presets and exported files are untouched. */
object LibraryRetention {
    const val limit=5
    fun removable(records:List<EditorRecord>,currentDraft:String):List<String>{
        val drafts=records.filter{it.kind=="draft"}.sortedWith(compareByDescending<EditorRecord>{it.updated}.thenByDescending{it.id})
        val current=drafts.firstOrNull{it.id==currentDraft}
        val keep=(listOfNotNull(current)+drafts.filter{it.id!=currentDraft}).take(limit).map{it.id}.toSet()
        val completed=records.filter{it.kind=="job"&&runCatching{JSONObject(it.payload).optString("status")=="done"}.getOrDefault(false)}
            .sortedWith(compareByDescending<EditorRecord>{it.updated}.thenByDescending{it.id})
        return drafts.filter{it.id !in keep}.map{it.id}+completed.drop(limit).map{it.id}
    }
}
