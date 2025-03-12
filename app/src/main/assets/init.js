function getRandomName() {
  return "__callback" + Math.round(Math.random() * 100000000);
}

async function callRpc(verb, args) {
  const prom = new Promise((resolve, reject) => {
    const some_random_name = getRandomName();
    window[some_random_name] = [resolve, reject];
    window.Android.callRpc(verb, JSON.stringify(args), some_random_name);
  });
  console.log("about to send out");
  let res = JSON.parse(await prom);
  return res;
}

window["NATIVE_GATE"] = {
  async start_daemon(daemon_args) {
    await callRpc("start_daemon", [daemon_args]);
  },

  async restart_daemon(daemon_args) {
    await callRpc("restart_daemon", [daemon_args]);
  },

  async stop_daemon() {
    await this.daemon_rpc("kill", []);
  },

  async is_running() {
    try {
      return (await this.daemon_rpc("conn_info", [])).state !== "Disconnected";
    } catch (e) {
      return false;
    }
  },

  async daemon_rpc(method, args) {
    const req = { jsonrpc: "2.0", method: method, params: args, id: 1 };
    const resp = await callRpc("daemon_rpc", [JSON.stringify(req)]);
    if (resp.error) {
      throw resp.error.message;
    }
    return resp.result;
  },

  async price_points() {
    return await this.daemon_rpc("price_points", []);
  },

  async create_invoice(secret, days) {
    throw "unsupported";
  },

  async pay_invoice(id, method) {
    throw "unsupported";
  },

  async sync_app_list() {
    const result = await callRpc("get_app_list", []);
    result.sort((a, b) => a.friendly_name.localeCompare(b.friendly_name));
    return result;
  },

  async get_app_icon_url(id) {
    return await callRpc("get_app_icon", [id]);
  },

  async export_debug_pack(email) {
    await callRpc("export_logs", [email]);
  },

  async get_debug_pack() {
    return await callRpc("get_debug_logs", []);
  },

  // Properties required by the interface
  supports_listen_all: true,
  supports_app_whitelist: true,
  supports_prc_whitelist: true,
  supports_proxy_conf: true,
  supports_vpn_conf: true,
  supports_autoupdate: true,

  async get_native_info() {
    return {
      platform_type: "android",
      platform_details: "Android",
      version: window.Android.jsVersion(),
    };
  },
};
