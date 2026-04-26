/*
 * Copyright (C) 2024 Charles Hartmann
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.charles.messenger.feature.settings.ai

import com.charles.messenger.model.AiModelOption
import com.charles.messenger.model.AiProvider

data class AiSettingsState(
    val aiEnabled: Boolean = false,
    val provider: AiProvider = AiProvider.OLLAMA,
    val ollamaUrl: String = "",
    val ollamaModel: String = "",
    val onDeviceModelName: String = "",
    val onDeviceModelPath: String = "",
    val availableModels: List<AiModelOption> = emptyList(),
    val loadingModels: Boolean = false,
    val installStatus: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Unknown,
    val autoReplyToAll: Boolean = false,
    val persona: String = "",
    val signatureEnabled: Boolean = false,
    val signatureText: String = ""
)

enum class ConnectionStatus {
    Unknown,
    Testing,
    Connected,
    Failed
}
