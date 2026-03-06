package com.panelpeek;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PanelPeekPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PanelPeekPlugin.class);
		RuneLite.main(args);
	}
}
