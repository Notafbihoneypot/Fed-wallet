#!/usr/bin/env python3
"""Run against a fresh offline, loopback-only Monero fakechain node."""
import argparse
import pathlib
import socket
import subprocess
import tempfile
import time
parser=argparse.ArgumentParser()
parser.add_argument("--monerod",required=True,type=pathlib.Path)
parser.add_argument("--debug",action="store_true",help="Reuse the debug profile from scripts/check.sh")
args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="independent-wallet-regtest-") as directory:
    with open(pathlib.Path(directory)/"node.log","w+") as log:
        command=[str(args.monerod.resolve()),"--regtest","--offline","--fixed-difficulty","1","--non-interactive","--no-zmq","--no-igd","--disable-dns-checkpoints","--data-dir",directory,"--rpc-bind-ip","127.0.0.1","--rpc-bind-port","48081","--p2p-bind-ip","127.0.0.1","--p2p-bind-port","48080","--log-level","0"]
        node=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if node.poll() is not None:
                    log.seek(0)
                    raise RuntimeError("Isolated regtest node failed: "+log.read()[-4000:])
                try:
                    with socket.create_connection(("127.0.0.1",48081),timeout=0.2):break
                except OSError:time.sleep(0.1)
            else:raise RuntimeError("Isolated regtest node did not start")
            raise SystemExit(subprocess.run(["cargo","test",*([] if args.debug else ["--release"]),"--locked","-p","wallet-engine","real_node_scan_sign_lost_reply_reopen_retry_mine_and_reorg","--","--ignored","--nocapture"],cwd=root).returncode)
        finally:
            node.terminate()
            try:node.wait(timeout=10)
            except subprocess.TimeoutExpired:
                node.kill()
                node.wait()
