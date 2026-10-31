package com.example.scedulealarm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scedulealarm.R
import com.example.scedulealarm.data.model.GroupItem
import com.example.scedulealarm.databinding.ItemGroupBottomSheetBinding

class GroupBottomSheetAdapter(
    private var groups: List<GroupItem> = emptyList(),
    private var selectedGroupName: String = "",
    private val onGroupSelected: (GroupItem) -> Unit
) : RecyclerView.Adapter<GroupBottomSheetAdapter.GroupViewHolder>() {

    fun updateData(newGroups: List<GroupItem>, selected: String) {
        this.groups = newGroups
        this.selectedGroupName = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupBottomSheetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position], selectedGroupName, onGroupSelected)
    }

    override fun getItemCount(): Int = groups.size

    class GroupViewHolder(private val binding: ItemGroupBottomSheetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            group: GroupItem,
            selectedName: String,
            onSelected: (GroupItem) -> Unit
        ) {
            val isSelected = group.name.equals(selectedName, ignoreCase = true)

            binding.tvGroupItemName.text = group.name
            binding.tvGroupItemFaculty.text = if (group.urlPath.startsWith("manual/")) {
                "Пользовательская группа (ручной ввод)"
            } else {
                "Учебная группа НГУ"
            }

            if (isSelected) {
                binding.layoutItemCard.setBackgroundResource(R.drawable.bg_item_card_selected)
                binding.viewRadioIndicator.setBackgroundResource(R.drawable.bg_radio_selected)
                binding.tvActiveBadge.visibility = View.VISIBLE
            } else {
                binding.layoutItemCard.setBackgroundResource(R.drawable.bg_item_card)
                binding.viewRadioIndicator.setBackgroundResource(R.drawable.bg_radio_unselected)
                binding.tvActiveBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onSelected(group)
            }
        }
    }
}
