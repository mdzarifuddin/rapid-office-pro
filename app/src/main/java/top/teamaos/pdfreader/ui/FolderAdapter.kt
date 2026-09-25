package top.teamaos.pdfreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.databinding.ItemFolderBinding
import java.io.File

/** A row in the file browser: a storage, a folder, or the step back up to the parent. */
data class FolderEntry(val dir: File, val name: String, val detail: String?, val isUp: Boolean = false)

/** The folders half of the Phone tab; the documents in the same folder follow in [DocumentAdapter]. */
class FolderAdapter(private val onOpen: (FolderEntry) -> Unit) :
    ListAdapter<FolderEntry, FolderAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        holder.binding.folderName.text = entry.name
        holder.binding.folderDetail.text = entry.detail.orEmpty()
        holder.binding.folderDetail.visibility = if (entry.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        holder.binding.folderIcon.setImageResource(if (entry.isUp) R.drawable.ic_arrow_up else R.drawable.ic_folder)
        holder.itemView.setOnClickListener { onOpen(entry) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<FolderEntry>() {
            override fun areItemsTheSame(a: FolderEntry, b: FolderEntry) = a.dir == b.dir && a.isUp == b.isUp
            override fun areContentsTheSame(a: FolderEntry, b: FolderEntry) = a == b
        }
    }
}
