package com.myai.assistant.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myai.assistant.R
import com.myai.assistant.core.model.ChatMessage
import com.myai.assistant.core.model.MessageKind
import com.myai.assistant.core.model.Role
import com.myai.assistant.core.model.StepStatus
import com.myai.assistant.core.model.Verification
import com.myai.assistant.databinding.ItemMessageAssistantBinding
import com.myai.assistant.databinding.ItemMessageInfoBinding
import com.myai.assistant.databinding.ItemMessageUserBinding
import com.myai.assistant.databinding.ItemStepBinding
import com.myai.assistant.util.TimeFormatter

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position).role) {
        Role.USER -> TYPE_USER
        Role.ASSISTANT -> TYPE_ASSISTANT
        Role.SYSTEM -> TYPE_INFO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(ItemMessageUserBinding.inflate(inflater, parent, false))
            TYPE_ASSISTANT -> AssistantViewHolder(ItemMessageAssistantBinding.inflate(inflater, parent, false))
            else -> InfoViewHolder(ItemMessageInfoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message)
            is InfoViewHolder -> holder.bind(message)
        }
    }

    private class UserViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.messageText.text = message.text
            binding.meta.text = TimeFormatter.clock(message.timestamp)
        }
    }

    private class InfoViewHolder(
        private val binding: ItemMessageInfoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.infoText.text = message.text
        }
    }

    private class AssistantViewHolder(
        private val binding: ItemMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val context = binding.root.context
            val hasText = message.text.isNotBlank()
            binding.messageText.isVisible = hasText || message.pending
            binding.messageText.text = when {
                hasText -> message.text
                message.pending -> context.getString(R.string.chat_thinking)
                else -> ""
            }
            binding.messageText.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (message.kind == MessageKind.ERROR) R.color.error else R.color.on_surface
                )
            )

            val plan = message.plan
            val hasPlan = plan != null && plan.steps.isNotEmpty()
            binding.planHeader.isVisible = hasPlan
            binding.stepsContainer.isVisible = hasPlan
            binding.stepsContainer.removeAllViews()
            plan?.steps?.forEachIndexed { index, step ->
                bindStep(binding, index, step)
            }

            binding.pending.isVisible = message.pending
            binding.meta.text = buildString {
                append(TimeFormatter.clock(message.timestamp))
                if (hasPlan) {
                    append(" • ")
                    append(context.getString(R.string.chat_plan_steps))
                    append(" (")
                    append(plan?.steps?.size ?: 0)
                    append(")")
                }
            }
        }

        private fun bindStep(
            parent: ItemMessageAssistantBinding,
            index: Int,
            step: com.myai.assistant.core.model.PlanStep
        ) {
            val context = parent.root.context
            val stepBinding = ItemStepBinding.inflate(
                LayoutInflater.from(context),
                parent.stepsContainer,
                true
            )

            val glyph = when (step.status) {
                StepStatus.PENDING -> "•"
                StepStatus.RUNNING -> "…"
                StepStatus.SUCCEEDED -> "✓"
                StepStatus.FAILED -> "✕"
                StepStatus.SKIPPED -> "–"
            }
            val colorRes = when (step.status) {
                StepStatus.RUNNING -> R.color.primary
                StepStatus.SUCCEEDED -> R.color.success
                StepStatus.FAILED -> R.color.error
                StepStatus.PENDING, StepStatus.SKIPPED -> R.color.muted
            }
            stepBinding.statusIcon.text = glyph
            stepBinding.statusIcon.setTextColor(ContextCompat.getColor(context, colorRes))

            val statusLabel = when (step.status) {
                StepStatus.PENDING -> R.string.step_pending
                StepStatus.RUNNING -> R.string.step_running
                StepStatus.SUCCEEDED -> R.string.step_succeeded
                StepStatus.FAILED -> R.string.step_failed
                StepStatus.SKIPPED -> R.string.step_skipped
            }
            val verificationLabel = when (step.verification) {
                Verification.VERIFIED -> R.string.verification_verified
                Verification.DISPATCHED -> R.string.verification_dispatched
                Verification.UNVERIFIED -> R.string.verification_unverified
                Verification.FAILED -> R.string.verification_failed
            }

            stepBinding.stepTitle.text = "${index + 1}. ${step.title}"
            stepBinding.stepMeta.text = buildString {
                append(context.getString(statusLabel))
                append(" • ")
                append(context.getString(verificationLabel))
                append(" • ")
                append(step.toolId)
                step.durationMs?.let {
                    append(" • ")
                    append(TimeFormatter.duration(it))
                }
            }
            stepBinding.stepMessage.isVisible = !step.resultMessage.isNullOrBlank()
            stepBinding.stepMessage.text = step.resultMessage.orEmpty()
        }
    }

    private companion object {
        const val TYPE_USER = 1
        const val TYPE_ASSISTANT = 2
        const val TYPE_INFO = 3

        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }
}
