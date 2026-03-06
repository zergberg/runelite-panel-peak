package com.panelpeek;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigSectionDescriptor;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * Swing dialog that renders a plugin's config items with live editing.
 * Values are persisted through ConfigManager immediately on change.
 */
public class ConfigDialog extends JDialog
{
	private static final Color BG = new Color(0x2b, 0x2b, 0x2b);
	private static final Color FG = new Color(0xbb, 0xbb, 0xbb);
	private static final Color SECTION_FG = new Color(0x9a, 0xab, 0xc0);

	private final ConfigDescriptor descriptor;
	private final ConfigManager configManager;
	private final String groupName;

	public ConfigDialog(String pluginName, ConfigDescriptor descriptor, ConfigManager configManager)
	{
		this.descriptor = descriptor;
		this.configManager = configManager;
		this.groupName = descriptor.getGroup().value();

		setTitle(pluginName + " Settings");
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setAlwaysOnTop(true);
		setResizable(true);

		JPanel content = new JPanel(new GridBagLayout());
		content.setBackground(BG);
		content.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

		buildControls(content);

		JScrollPane scroll = new JScrollPane(content);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(BG);
		scroll.setPreferredSize(new Dimension(380, 420));

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(scroll, BorderLayout.CENTER);

		// Close button at the bottom
		JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		bottom.setBackground(BG);
		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(e -> dispose());
		bottom.add(closeBtn);
		getContentPane().add(bottom, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(null);
	}

	private void buildControls(JPanel panel)
	{
		// Group items by section
		Map<String, List<ConfigItemDescriptor>> sections = new LinkedHashMap<>();
		Map<String, String> sectionDisplayNames = new LinkedHashMap<>();

		// Map section key -> display name
		for (ConfigSectionDescriptor sectionDesc : descriptor.getSections())
		{
			String key = sectionDesc.key();
			sectionDisplayNames.put(key, sectionDesc.name());
			sections.put(key, new ArrayList<>());
		}

		// Sort items into sections
		List<ConfigItemDescriptor> unsectioned = new ArrayList<>();
		for (ConfigItemDescriptor item : descriptor.getItems())
		{
			if (item.getItem().hidden())
			{
				continue;
			}

			String section = item.getItem().section();
			if (section != null && !section.isEmpty() && sections.containsKey(section))
			{
				sections.get(section).add(item);
			}
			else
			{
				unsectioned.add(item);
			}
		}

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(3, 0, 3, 0);
		int row = 0;

		// Render unsectioned items first
		for (ConfigItemDescriptor item : unsectioned)
		{
			row = addConfigRow(panel, c, row, item);
		}

		// Render each section
		for (Map.Entry<String, List<ConfigItemDescriptor>> entry : sections.entrySet())
		{
			List<ConfigItemDescriptor> items = entry.getValue();
			if (items.isEmpty())
			{
				continue;
			}

			String displayName = sectionDisplayNames.getOrDefault(entry.getKey(), entry.getKey());
			row = addSectionHeader(panel, c, row, displayName);

			for (ConfigItemDescriptor item : items)
			{
				row = addConfigRow(panel, c, row, item);
			}
		}

		// Filler to push everything to the top
		c.gridx = 0;
		c.gridy = row;
		c.weighty = 1.0;
		panel.add(new JLabel(), c);
	}

	private int addSectionHeader(JPanel panel, GridBagConstraints c, int row, String name)
	{
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 2;
		c.weightx = 1.0;
		c.weighty = 0;
		c.insets = new Insets(10, 0, 2, 0);

		JLabel label = new JLabel(name);
		label.setForeground(SECTION_FG);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
		panel.add(label, c);
		row++;

		c.gridy = row;
		c.insets = new Insets(0, 0, 6, 0);
		JSeparator sep = new JSeparator();
		sep.setForeground(SECTION_FG.darker());
		panel.add(sep, c);
		row++;

		c.gridwidth = 1;
		c.insets = new Insets(3, 0, 3, 0);
		return row;
	}

	private int addConfigRow(JPanel panel, GridBagConstraints c, int row, ConfigItemDescriptor item)
	{
		ConfigItem annotation = item.getItem();

		// Label
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0.4;
		c.weighty = 0;
		c.gridwidth = 1;

		JLabel label = new JLabel(annotation.name());
		label.setForeground(FG);
		String tooltip = annotation.description();
		if (tooltip != null && !tooltip.isEmpty())
		{
			label.setToolTipText(tooltip);
		}
		panel.add(label, c);

		// Control
		c.gridx = 1;
		c.weightx = 0.6;

		javax.swing.JComponent control = createControl(item);
		if (control != null)
		{
			JPanel controlWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			controlWrapper.setOpaque(false);
			controlWrapper.add(control);
			panel.add(controlWrapper, c);
		}

		return row + 1;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private javax.swing.JComponent createControl(ConfigItemDescriptor item)
	{
		java.lang.reflect.Type rawType = item.getType();
		if (!(rawType instanceof Class<?>))
		{
			// Parameterized or generic type — fall back to text field
			String key = item.getItem().keyName();
			String currentValue = configManager.getConfiguration(groupName, key);
			JTextField field = new JTextField(currentValue != null ? currentValue : "");
			field.setPreferredSize(new Dimension(160, 24));
			field.addActionListener(e ->
				configManager.setConfiguration(groupName, key, field.getText()));
			field.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					configManager.setConfiguration(groupName, key, field.getText());
				}
			});
			return field;
		}

		Class<?> type = (Class<?>) rawType;
		String key = item.getItem().keyName();
		String currentValue = configManager.getConfiguration(groupName, key);

		// Boolean
		if (type == boolean.class || type == Boolean.class)
		{
			JCheckBox cb = new JCheckBox();
			cb.setSelected(currentValue != null && Boolean.parseBoolean(currentValue));
			cb.setOpaque(false);
			cb.addActionListener(e ->
				configManager.setConfiguration(groupName, key, String.valueOf(cb.isSelected())));
			return cb;
		}

		// Integer
		if (type == int.class || type == Integer.class)
		{
			int val = 0;
			try
			{
				if (currentValue != null)
				{
					val = Integer.parseInt(currentValue);
				}
			}
			catch (NumberFormatException ignored)
			{
			}

			int min = Integer.MIN_VALUE;
			int max = Integer.MAX_VALUE;
			Range range = item.getRange();
			if (range != null)
			{
				min = range.min();
				max = range.max();
			}

			JSpinner spinner = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
			spinner.setPreferredSize(new Dimension(100, 24));
			spinner.addChangeListener(e ->
				configManager.setConfiguration(groupName, key, String.valueOf(spinner.getValue())));

			// Append units label if present
			Units units = item.getUnits();
			if (units != null)
			{
				JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
				p.setOpaque(false);
				p.add(spinner);
				JLabel unitLabel = new JLabel(units.value());
				unitLabel.setForeground(FG.darker());
				p.add(unitLabel);
				return p;
			}

			return spinner;
		}

		// Double
		if (type == double.class || type == Double.class)
		{
			double val = 0.0;
			try
			{
				if (currentValue != null)
				{
					val = Double.parseDouble(currentValue);
				}
			}
			catch (NumberFormatException ignored)
			{
			}

			JSpinner spinner = new JSpinner(new SpinnerNumberModel(val, -Double.MAX_VALUE, Double.MAX_VALUE, 0.1));
			spinner.setPreferredSize(new Dimension(100, 24));
			spinner.addChangeListener(e ->
				configManager.setConfiguration(groupName, key, String.valueOf(spinner.getValue())));
			return spinner;
		}

		// Enum
		if (type.isEnum())
		{
			JComboBox<Object> combo = new JComboBox<>(type.getEnumConstants());
			if (currentValue != null)
			{
				try
				{
					combo.setSelectedItem(Enum.valueOf((Class<Enum>) type, currentValue));
				}
				catch (Exception ignored)
				{
				}
			}
			combo.addActionListener(e ->
			{
				Object selected = combo.getSelectedItem();
				if (selected instanceof Enum)
				{
					configManager.setConfiguration(groupName, key, ((Enum<?>) selected).name());
				}
			});
			return combo;
		}

		// Color
		if (type == Color.class)
		{
			Color initial = Color.WHITE;
			if (currentValue != null)
			{
				try
				{
					initial = new Color(Integer.parseInt(currentValue), true);
				}
				catch (NumberFormatException ignored)
				{
				}
			}

			JButton colorBtn = new JButton();
			colorBtn.setPreferredSize(new Dimension(40, 24));
			colorBtn.setBackground(initial);
			colorBtn.setBorderPainted(true);
			colorBtn.addActionListener(e ->
			{
				Color chosen = JColorChooser.showDialog(this, "Choose Color", colorBtn.getBackground());
				if (chosen != null)
				{
					colorBtn.setBackground(chosen);
					configManager.setConfiguration(groupName, key, String.valueOf(chosen.getRGB()));
				}
			});
			return colorBtn;
		}

		// Fallback: text field for String and anything else
		JTextField field = new JTextField(currentValue != null ? currentValue : "");
		field.setPreferredSize(new Dimension(160, 24));
		field.addActionListener(e ->
			configManager.setConfiguration(groupName, key, field.getText()));
		field.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				configManager.setConfiguration(groupName, key, field.getText());
			}
		});
		return field;
	}
}
