/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.composition

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * A [RecyclerView.Adapter] that displays assets in a sequence in a [RecyclerView].
 *
 * @param data A list of items to populate RecyclerView with.
 */
class AssetItemAdapter(private val data: MutableList<String>) :
    RecyclerView.Adapter<AssetItemAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.preset_item, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = data[position]
    }

    override fun getItemCount(): Int {
        return data.size
    }

    /** A [RecyclerView.ViewHolder] used to build [AssetItemAdapter].  */
    class ViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {
        internal val textView: TextView = view.findViewById<TextView>(R.id.preset_name_text)
    }

    companion object {
        private const val TAG = "AssetItemAdapter"
    }
}
