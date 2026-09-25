package top.teamaos.pdfreader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import top.teamaos.pdfreader.core.DocumentController
import top.teamaos.pdfreader.databinding.ItemPageThumbBinding
import top.teamaos.pdfreader.databinding.SheetThumbnailsBinding
import kotlin.math.max

/**
 * A grid of page thumbnails for jumping around a long document.
 *
 * Thumbnails come from the same cache the reader fills while scrolling, so pages already seen show
 * up instantly and the rest are queued at low priority — the grid never renders anything itself and
 * never competes with the page being read.
 */
object ThumbnailsSheet {

    private const val COLUMNS = 3

    fun show(context: Context, controller: DocumentController, currentPage: Int, onPick: (Int) -> Unit) {
        val binding = SheetThumbnailsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        val adapter = ThumbnailAdapter(controller, currentPage) { page ->
            dialog.dismiss()
            onPick(page)
        }
        binding.thumbnailGrid.layoutManager = GridLayoutManager(context, COLUMNS)
        binding.thumbnailGrid.adapter = adapter

        // Redraw as thumbnails land, then stop listening the moment the sheet closes.
        val listener: () -> Unit = { adapter.refreshVisible(binding.thumbnailGrid) }
        controller.addInvalidateListener(listener)
        dialog.setOnDismissListener { controller.removeInvalidateListener(listener) }

        binding.thumbnailGrid.scrollToPosition(max(0, currentPage - COLUMNS))
        dialog.show()
    }

    private class ThumbnailAdapter(
        val controller: DocumentController,
        val currentPage: Int,
        val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ThumbnailAdapter.Holder>() {

        class Holder(val binding: ItemPageThumbBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount(): Int = controller.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPageThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.binding.pageLabel.text = (position + 1).toString()
            holder.binding.currentOutline.visibility =
                if (position == currentPage) View.VISIBLE else View.GONE

            val thumb = controller.cache.thumb(position)
            if (thumb != null) {
                holder.binding.pageImage.setImageBitmap(thumb)
            } else {
                holder.binding.pageImage.setImageDrawable(null)
                val aspect = controller.geometry.widthPts(position) /
                    controller.geometry.heightPts(position).coerceAtLeast(1f)
                // Far behind the reader's own work: a grid the user is browsing can wait.
                controller.renderer.requestThumb(position, aspect, THUMBNAIL_PRIORITY)
            }
            holder.itemView.setOnClickListener { onPick(position) }
        }

        /** Rebind only what is on screen; the grid can be thousands of items long. */
        fun refreshVisible(recycler: RecyclerView) {
            val manager = recycler.layoutManager as? GridLayoutManager ?: return
            val first = manager.findFirstVisibleItemPosition()
            val last = manager.findLastVisibleItemPosition()
            if (first < 0 || last < first) return
            notifyItemRangeChanged(first, last - first + 1)
        }

        private companion object {
            const val THUMBNAIL_PRIORITY = 5000
        }
    }
}
