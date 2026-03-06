package com.panelpeek;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OpenMode
{
	SIDE_PANEL("Side Panel"),
	DIALOG("Popup Dialog");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
