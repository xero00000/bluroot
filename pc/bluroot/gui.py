"""Desktop window for lending the phone radio to BlueZ."""

from __future__ import annotations

import queue
import tkinter as tk
from tkinter import messagebox, ttk

from bluroot.discover import Discovery, Phone
from bluroot.link import LinkError, Session, adb_devices

BG = "#071018"
CARD = "#122033"
FG = "#E7EEF7"
MUTED = "#93A4B8"
ACCENT = "#4C9AFF"
FIELD = "#0C1826"


def run_gui() -> None:
    App().run()


class App:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("BluRoot")
        self.root.geometry("760x680")
        self.root.minsize(640, 560)
        self.root.configure(bg=BG)
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.session = Session(lambda kind, payload: self.events.put((kind, payload)))
        self.discovery = Discovery()
        self.discovery.start()
        self._phones: list[Phone] = []
        self._serials: list[str] = []
        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self._close)
        self.root.after(200, self._poll)

    def run(self) -> None:
        self.root.mainloop()

    def _build(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", background=BG, foreground=FG, fieldbackground=FIELD)
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=CARD)
        style.configure("TLabel", background=BG, foreground=FG)
        style.configure("Card.TLabel", background=CARD, foreground=FG)
        style.configure("Muted.TLabel", background=BG, foreground=MUTED)
        style.configure("CardMuted.TLabel", background=CARD, foreground=MUTED)
        style.configure("TRadiobutton", background=CARD, foreground=FG)
        style.configure("TCheckbutton", background=CARD, foreground=FG)
        style.configure("TButton", background=ACCENT, foreground="#041018", padding=8)
        style.configure("TEntry", fieldbackground=FIELD, foreground=FG)
        style.map("TButton", background=[("active", "#78B4FF")])

        outer = ttk.Frame(self.root, padding=20)
        outer.pack(fill="both", expand=True)

        ttk.Label(outer, text="BluRoot", font=("Sans", 22, "bold")).pack(anchor="w")
        ttk.Label(
            outer,
            text="Lend your phone's Bluetooth radio to this PC. Pair devices here after it connects.",
            style="Muted.TLabel",
            wraplength=700,
        ).pack(anchor="w", pady=(4, 14))

        card = ttk.Frame(outer, style="Card.TFrame", padding=16)
        card.pack(fill="x")

        self.mode = tk.StringVar(value="usb")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Radiobutton(row, text="USB", value="usb", variable=self.mode, command=self._refresh).pack(side="left")
        ttk.Radiobutton(row, text="Wi-Fi", value="wifi", variable=self.mode, command=self._refresh).pack(
            side="left", padx=(16, 0)
        )

        self.usb_box = ttk.Frame(card, style="Card.TFrame")
        self.usb_box.pack(fill="x", pady=(12, 0))
        ttk.Label(self.usb_box, text="Phone", style="CardMuted.TLabel").pack(anchor="w")
        self.serials = ttk.Combobox(self.usb_box, state="readonly")
        self.serials.pack(fill="x", pady=(4, 0))
        ttk.Label(
            self.usb_box,
            text="Plug in the phone, allow USB debugging, and tap Start in the phone app.",
            style="CardMuted.TLabel",
            wraplength=680,
        ).pack(anchor="w", pady=(6, 0))

        self.wifi_box = ttk.Frame(card, style="Card.TFrame")
        ttk.Label(self.wifi_box, text="Nearby phones", style="CardMuted.TLabel").pack(anchor="w")
        self.phone_list = tk.Listbox(
            self.wifi_box,
            height=4,
            bg=FIELD,
            fg=FG,
            selectbackground=ACCENT,
            selectforeground="#041018",
            highlightthickness=0,
            relief="flat",
        )
        self.phone_list.pack(fill="x", pady=(4, 8))
        self.phone_list.bind("<<ListboxSelect>>", self._pick_phone)
        grid = ttk.Frame(self.wifi_box, style="Card.TFrame")
        grid.pack(fill="x")
        ttk.Label(grid, text="IP address", style="CardMuted.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(grid, text="Port", style="CardMuted.TLabel").grid(row=0, column=1, sticky="w", padx=(12, 0))
        self.host = ttk.Entry(grid)
        self.host.grid(row=1, column=0, sticky="ew", pady=(4, 0))
        self.port = ttk.Entry(grid, width=8)
        self.port.insert(0, "8877")
        self.port.grid(row=1, column=1, sticky="w", padx=(12, 0), pady=(4, 0))
        grid.columnconfigure(0, weight=1)

        code_row = ttk.Frame(card, style="Card.TFrame")
        code_row.pack(fill="x", pady=(12, 0))
        ttk.Label(code_row, text="Code from the phone", style="CardMuted.TLabel").pack(anchor="w")
        self.code = ttk.Entry(code_row, font=("Sans", 14))
        self.code.pack(fill="x", pady=(4, 0))
        ttk.Label(
            code_row,
            text="USB can read the code from the phone. Wi-Fi needs you to type it.",
            style="CardMuted.TLabel",
        ).pack(anchor="w", pady=(6, 0))

        buttons = ttk.Frame(outer)
        buttons.pack(fill="x", pady=(14, 8))
        self.prepare = ttk.Button(buttons, text="Prepare adapter", command=self._prepare)
        self.prepare.pack(side="left")
        self.start = ttk.Button(buttons, text="Connect", command=self._connect)
        self.start.pack(side="left", padx=(8, 0))
        self.stop = ttk.Button(buttons, text="Disconnect", command=self._disconnect, state="disabled")
        self.stop.pack(side="left", padx=(8, 0))

        self.status = ttk.Label(outer, text="Idle", style="Muted.TLabel")
        self.status.pack(anchor="w")
        self.counts = ttk.Label(outer, text="", style="Muted.TLabel")
        self.counts.pack(anchor="w", pady=(2, 8))

        self.log = tk.Text(
            outer,
            height=12,
            bg=CARD,
            fg=FG,
            insertbackground=FG,
            relief="flat",
            wrap="word",
            padx=10,
            pady=10,
        )
        self.log.pack(fill="both", expand=True)
        self.log.configure(state="disabled")
        self._refresh()
        self._scan_adb()

    def _refresh(self) -> None:
        if self.mode.get() == "usb":
            self.wifi_box.pack_forget()
            self.usb_box.pack(fill="x", pady=(12, 0))
        else:
            self.usb_box.pack_forget()
            self.wifi_box.pack(fill="x", pady=(12, 0))

    def _scan_adb(self) -> None:
        try:
            self._serials = adb_devices()
        except LinkError as error:
            self._serials = []
            self._write(str(error))
        self.serials["values"] = self._serials
        if self._serials and not self.serials.get():
            self.serials.set(self._serials[0])

    def _pick_phone(self, _event: object) -> None:
        selection = self.phone_list.curselection()
        if not selection:
            return
        phone = self._phones[selection[0]]
        self.host.delete(0, "end")
        self.host.insert(0, phone.address)
        self.port.delete(0, "end")
        self.port.insert(0, str(phone.port))

    def _connect(self) -> None:
        self.start.configure(state="disabled")
        mode = self.mode.get()
        code = self.code.get().strip()
        try:
            port = int(self.port.get() or "8877")
        except ValueError:
            self.start.configure(state="normal")
            messagebox.showerror("BluRoot", "Port must be a number")
            return

        def work() -> None:
            try:
                if mode == "usb":
                    serial = self.serials.get() or None
                    self.session.start_usb(serial, port, code)
                else:
                    host = self.host.get().strip()
                    if not host:
                        raise LinkError("Choose a phone or enter its IP address")
                    self.session.start_wifi(host, port, code)
            except LinkError as error:
                self.events.put(("error", str(error)))

        self._write("Connecting")
        self.root.after(0, lambda: None)
        import threading

        threading.Thread(target=work, daemon=True).start()

    def _disconnect(self) -> None:
        self.stop.configure(state="disabled")
        import threading

        threading.Thread(target=self.session.stop, daemon=True).start()

    def _prepare(self) -> None:
        import threading

        from bluroot.vhci import VhciError, ensure_access

        def work() -> None:
            try:
                ensure_access()
            except VhciError as error:
                self.events.put(("error", str(error)))
                return
            self.events.put(("log", "Virtual Bluetooth adapter is available"))

        threading.Thread(target=work, daemon=True).start()

    def _poll(self) -> None:
        while True:
            try:
                kind, payload = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "log":
                self._write(str(payload))
            elif kind == "status":
                self.status.configure(text=str(payload))
                self._write(str(payload))
            elif kind == "index":
                self._write(f"BlueZ adapter hci{payload} is up. Pair devices from this PC.")
            elif kind == "counts":
                to_phone, to_pc = payload  # type: ignore[misc]
                self.counts.configure(text=f"To phone {to_phone}    To PC {to_pc}")
            elif kind == "error":
                self.start.configure(state="normal")
                self.stop.configure(state="disabled")
                self.status.configure(text=str(payload))
                self._write(str(payload))
                messagebox.showerror("BluRoot", str(payload))
            elif kind == "stopped":
                self.start.configure(state="normal")
                self.stop.configure(state="disabled")

        if self.session.running:
            self.start.configure(state="disabled")
            self.stop.configure(state="normal")

        phones = self.discovery.phones()
        labels = [f"{phone.name}   {phone.address}:{phone.port}   {phone.state}" for phone in phones]
        current = self.phone_list.get(0, "end")
        if tuple(labels) != tuple(current):
            self.phone_list.delete(0, "end")
            for label in labels:
                self.phone_list.insert("end", label)
            self._phones = phones
        if self.discovery.error and not getattr(self, "_said_discovery", False):
            self._said_discovery = True
            self._write(self.discovery.error)
        self.root.after(400, self._poll)

    def _write(self, line: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", line + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _close(self) -> None:
        if self.session.running:
            self.session.stop()
        self.discovery.stop()
        self.root.destroy()
