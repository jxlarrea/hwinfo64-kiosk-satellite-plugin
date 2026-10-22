# Changelog

## Unreleased

### Added

- Read the sensors of one or more PCs through HWiNFO's Remote Sensor Monitoring on TCP port 27007, with a Test button that reports what each PC answers.
- Send picked sensors to Home Assistant with units and device classes. Yes or no sensors become binary sensors and every PC gets an online sensor.
- Pick sensors from one searchable list of every PC's sensors, grouped by computer and device and showing current values.
- Add the PC sensors screensaver, drawn natively, with panels, stacked columns, footer logos, value statistics, red limits, colors and text size. Build makes a starter layout from a PC's sensors.
- Switch to PC sensors automatically while a sensor stays past a limit, such as the CPU temperature, and hand the screensaver back afterward. The Show PC sensors action starts it on demand.
- Add the Installation section with the Install in Kiosk Satellite button and the manual steps.
- Use the official HWiNFO logo as the plugin icon.
- Rename the plugin to HWiNFO Monitor.
- Name HWiNFO's sensors the way HWiNFO does and type the automatic screensaver and red tile limits as numbers.
- Show the values sent to Home Assistant inside the Home Assistant group.
- Draw the screensaver in the Kiosk Satellite look with its typeface, radii and spacing. It follows the Kiosk Satellite theme in light and dark until Custom colors is on, which adds a text color.
- Add Value size for the sensor values and Label size for the labels under them.
- Add the EK and Seasonic footer logos.
- Tune what 100% means for Text size, Value size and Label size.
- Add Theme to show the screensaver light or dark, or Auto to follow the Kiosk Satellite theme.
- Show light and dark screenshots in the README and name the Kiosk Satellite version the plugin needs.
- Keep the values in the sensor pickers fresh while a settings page is open, and head a renamed computer's sensors with its new name.
- Add Overview tile to show or hide the PCs online tile on the Remote Admin Overview.
- Open the README screenshots at full size when clicked.
- Add combined sensors, made of several sensors by maximum, minimum, sum or average and picked like any other sensor.
- Tell people to add a computer first when a sensor picker has nothing to list yet.
- Turn the sensor and computer pickers off while no computer answers.
- Match number ranges such as `{0-7}` in combined sensor names and rename the field to Match sensor name.
- Show every sensor value and every label at one size, so Value size and Label size change them all alike.
- Bring the panel titles closer to the top of their panels.
- Add Header color and Label color to Custom colors.
- Add a chart to panels that follows up to three sensors over the last 1 to 60 minutes. It draws only while the screensaver shows.
- Pick a panel's chart lines in its Sensors list with Show as, so each line has its own label and an optional color. The chart now marks round values along its sides and the time below, with room above its legend.
- Add Chart label size.
- Show the chart's values in the muted text color, with more room around the chart, without the time labels and with their unit only when Units on the chart scale is on.
- Add Fixed chart scale with Chart minimum and Chart maximum to chart lines, so a chart can keep one scale instead of following its values.
- Move the screensaver's look into its own Screensaver appearance group, so Build sits right under Panels.
