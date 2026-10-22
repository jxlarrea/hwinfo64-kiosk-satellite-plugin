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
