package com.panelpeek;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("panelpeek")
public interface PanelPeekConfig extends Config
{
	@ConfigItem(
		keyName = "openMode",
		name = "Open Mode",
		description = "How to open plugin settings: in the RuneLite side panel or a popup dialog"
	)
	default OpenMode openMode()
	{
		return OpenMode.SIDE_PANEL;
	}
}
