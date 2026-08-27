package mod.kelvinlby.vcam;

import mod.kelvinlby.OpenCrafterLink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Discovery of writable V4L2 loopback video nodes — the OS-level sink a virtual camera feeds.
 *
 * <h2>Why Linux only</h2>
 * A real virtual camera device cannot be created from inside a mod jar on the other platforms.
 * Windows needs a DirectShow filter DLL registered with an administrator {@code regsvr32}; macOS
 * (Ventura and later, where the old DAL plug-ins are disabled) needs a CoreMediaIO Camera Extension
 * shipped in a signed app bundle carrying Apple's {@code system-extension.install} entitlement.
 * Neither is shippable here, and {@code ffmpeg} cannot write a camera on either platform anyway —
 * its {@code dshow}/{@code avfoundation} support is capture-only. Linux, by contrast, needs
 * <b>no new dependency at all</b>: {@code v4l2loopback} exposes a writable {@code /dev/videoN} and
 * the system ffmpeg this mod already pipes to has the {@code v4l2} <em>output</em> muxer. So
 * {@link #supported()} gates the whole feature and the settings screen disables the toggles
 * elsewhere.
 *
 * <h2>Finding the loopback nodes</h2>
 * Loopback nodes are enumerated through {@code /sys/devices/virtual/video4linux}, which — being the
 * <em>virtual</em> device tree — lists exactly the software devices and never a real UVC webcam. That
 * distinction matters: writing raw frames into a physical capture device would fail, and guessing by
 * {@code /dev/video*} index alone cannot tell the two apart. Each node's {@code name} attribute is
 * the {@code card_label} the user sees in OBS, so it is carried along for logs and the settings
 * screen.
 *
 * <p>The kernel module is <b>not</b> loaded by default on most distributions and a mod cannot install
 * it, so absence is an expected state, not an error: {@link #moduleLoaded()} reports it and the
 * settings screen surfaces {@link #setupHint()} instead of failing.
 *
 * @param path  the character device to write frames into, e.g. {@code /dev/video10}
 * @param label the device's {@code card_label}, as shown by consumer applications
 */
public record V4l2Device(Path path, String label) {

	/** The sysfs virtual-device tree; only software video devices appear here. */
	private static final Path SYSFS_VIRTUAL = Path.of("/sys/devices/virtual/video4linux");

	/** Whether virtual cameras can work on this OS at all (see the class javadoc). */
	public static boolean supported() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return !os.contains("win") && !os.contains("mac");
	}

	/**
	 * Whether this client runs inside a Flatpak sandbox (Prism Launcher and friends), mirroring the
	 * detection {@code LinkConfig.resolveUdsDir} already uses for socket placement. Worth
	 * distinguishing because a sandbox without {@code --device=all} hides {@code /dev/video*}
	 * entirely — indistinguishable from a missing module by enumeration alone, but needing a
	 * completely different fix, so {@link #setupHint()} must not send the user to {@code modprobe}.
	 */
	public static boolean inFlatpak() {
		String id = System.getenv("FLATPAK_ID");
		return (id != null && !id.isBlank()) || Files.exists(Path.of("/.flatpak-info"));
	}

	/** Whether at least one loopback node exists, i.e. {@code v4l2loopback} appears to be loaded. */
	public static boolean moduleLoaded() {
		return !discover().isEmpty();
	}

	/**
	 * Every loopback node present, in stable index order. Includes nodes already claimed by another
	 * process — {@link #writable()} narrows to the usable ones.
	 */
	public static List<V4l2Device> discover() {
		if (!supported() || !Files.isDirectory(SYSFS_VIRTUAL)) {
			return List.of();
		}
		List<V4l2Device> found = new ArrayList<>();
		try (Stream<Path> entries = Files.list(SYSFS_VIRTUAL)) {
			for (Path entry : entries.toList()) {
				String node = entry.getFileName().toString();
				if (!node.startsWith("video")) {
					continue;
				}
				Path dev = Path.of("/dev", node);
				if (!Files.exists(dev)) {
					continue; // sysfs entry without a device node (racing udev); skip
				}
				found.add(new V4l2Device(dev, readLabel(entry, node)));
			}
		} catch (IOException e) {
			OpenCrafterLink.LOGGER.warn("[open-crafter-link] could not enumerate v4l2 loopback devices", e);
			return List.of();
		}
		found.sort(Comparator.comparing(d -> nodeIndex(d.path())));
		return List.copyOf(found);
	}

	/** Loopback nodes this process can actually open for writing. */
	public static List<V4l2Device> writable() {
		return discover().stream().filter(d -> Files.isWritable(d.path())).toList();
	}

	/** The module's {@code exclusive_caps} array, e.g. {@code Y,N,N,...}; empty when unreadable. */
	private static final Path EXCLUSIVE_CAPS_PARAM =
			Path.of("/sys/module/v4l2loopback/parameters/exclusive_caps");

	/**
	 * Which loopback nodes were loaded <b>without</b> {@code exclusive_caps}, in the same stable index
	 * order {@link #writable()} hands out — so the caller can name the offending camera.
	 *
	 * <h2>Why this needs checking at all</h2>
	 * A node without exclusive caps advertises {@code Video Capture} <em>and</em> {@code Video Output}
	 * at once. Streaming to it succeeds and ffmpeg reports no error, but Discord, Chromium and other
	 * consumers refuse to start capture on such a node (Discord shows "error 2011"). The failure is
	 * therefore completely invisible from this side: the camera looks healthy in the log and in the
	 * settings screen while the consumer sees a broken device.
	 *
	 * <p>The common way to land here is shell quoting. {@code card_label="A","B"} loses its quotes to
	 * the shell before {@code modprobe} sees them, and the mangled argument list takes
	 * {@code exclusive_caps=1,1} down with it — leaving {@code Y,N} where {@code Y,Y} was intended, so
	 * the <em>second</em> camera (depth) is the one that breaks while the first still works.
	 *
	 * <h2>Why the parameter array and not an ioctl</h2>
	 * The per-node capability flags are only exposed through {@code VIDIOC_QUERYCAP}, which needs an
	 * ioctl this mod cannot issue from pure Java. The module's parameter array is plain readable text
	 * and is applied positionally at load, matching the node ordering {@link #discover()} already
	 * sorts by, so it answers the same question without a native dependency.
	 */
	public static List<V4l2Device> withoutExclusiveCaps() {
		String raw;
		try {
			raw = Files.readString(EXCLUSIVE_CAPS_PARAM).trim();
		} catch (IOException e) {
			return List.of(); // parameter absent (foreign build); nothing can be claimed either way
		}
		String[] flags = raw.split(",");
		List<V4l2Device> nodes = discover();
		List<V4l2Device> bad = new ArrayList<>();
		for (int i = 0; i < nodes.size() && i < flags.length; i++) {
			if (!flags[i].trim().equalsIgnoreCase("Y")) {
				bad.add(nodes.get(i));
			}
		}
		return List.copyOf(bad);
	}

	/** The {@code card_label} from sysfs, falling back to the node name if unreadable. */
	private static String readLabel(Path sysfsEntry, String fallback) {
		try {
			return Files.readString(sysfsEntry.resolve("name")).trim();
		} catch (IOException e) {
			return fallback;
		}
	}

	/** Numeric suffix of a {@code /dev/videoN} path, for stable ordering ({@code MAX_VALUE} if absent). */
	private static int nodeIndex(Path dev) {
		String digits = dev.getFileName().toString().replaceAll("\\D+", "");
		try {
			return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	/**
	 * A one-line, copy-pasteable explanation of why no device is available, shown in the settings
	 * screen. Returns null when devices are present and nothing needs saying.
	 */
	public static String setupHint() {
		if (!supported()) {
			return "Virtual cameras are Linux-only (Windows needs a signed DirectShow driver, "
					+ "macOS a signed Camera Extension).";
		}
		if (moduleLoaded()) {
			return null;
		}
		if (inFlatpak()) {
			return "No video devices visible in this Flatpak sandbox. Grant device access with: "
					+ "flatpak override --user --device=all <launcher-id>  (then load v4l2loopback on the host).";
		}
		return "v4l2loopback not loaded. Run: " + recommendedModprobe();
	}

	/**
	 * The recommended module command. {@code devices=2} because the RGB and depth cameras each need
	 * their own node, and {@code exclusive_caps=1,1} — <b>one flag per device</b> — because Discord,
	 * Chromium and other consumers ignore or fail on a loopback node that advertises both capture and
	 * output capabilities; the device then appears in the picker but cannot start ("error 2011" in
	 * Discord). With exclusive caps the node advertises capture only, at the cost of being invisible
	 * until the mod actually streams to it.
	 *
	 * <h2>Why the labels carry no quotes or spaces</h2>
	 * This string is meant to be pasted into a shell. Quoted labels
	 * ({@code card_label="Minecraft RGB","Minecraft Depth"}) do not survive that trip: the shell strips
	 * the quotes and the embedded space splits the argument, so {@code modprobe} receives a mangled
	 * list and silently applies only the <em>first</em> {@code exclusive_caps} flag. The result is a
	 * working RGB camera and a depth node stuck with both capabilities — the exact state this option
	 * exists to avoid, reported as {@link #withoutExclusiveCaps()}. Underscored labels are
	 * single-token, so they paste correctly and the flags land on both devices.
	 */
	public static String recommendedModprobe() {
		return "sudo modprobe -r v4l2loopback && sudo modprobe v4l2loopback devices=2 "
				+ "exclusive_caps=1,1 card_label=Minecraft_RGB,Minecraft_Depth";
	}
}
