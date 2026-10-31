package com.example.scedulealarm.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scedulealarm.databinding.ItemSubjectFilterBinding

class SubjectFilterBottomSheetAdapter(
    private val subjects: List<String>,
    private val selectedSubjects: MutableSet<String> // subjects that are checked (visible)
) : RecyclerView.Adapter<SubjectFilterBottomSheetAdapter.SubjectViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val binding = ItemSubjectFilterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SubjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        holder.bind(subject, selectedSubjects.contains(subject)) { isChecked ->
            if (isChecked) {
                selectedSubjects.add(subject)
            } else {
                selectedSubjects.remove(subject)
            }
        }
    }

    override fun getItemCount(): Int = subjects.size

    fun selectAll() {
        selectedSubjects.clear()
        selectedSubjects.addAll(subjects)
        notifyDataSetChanged()
    }

    class SubjectViewHolder(private val binding: ItemSubjectFilterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            subject: String,
            isChecked: Boolean,
            onCheckedChange: (Boolean) -> Unit
        ) {
            binding.tvFilterSubjectTitle.text = subject
            binding.cbSubjectFilter.isChecked = isChecked

            binding.root.setOnClickListener {
                val newState = !binding.cbSubjectFilter.isChecked
                binding.cbSubjectFilter.isChecked = newState
                onCheckedChange(newState)
            }
        }
    }
}
