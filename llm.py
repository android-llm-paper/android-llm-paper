from concurrent.futures import ThreadPoolExecutor, as_completed
import os, json
import pymongo.collection
import requests
import pymongo, bson
from dotenv import load_dotenv

load_dotenv()
oneapi_token = "Bearer " + os.getenv("ONEAPI_TOKEN")
oneapi_chat_url = "https://one-api.m.reimu.host/v1/chat/completions"
ohmygpt_token = "Bearer " + os.getenv("OHMYGPT_TOKEN")
ohmygpt_chat_url = "https://cn2us02.opapi.win/v1/chat/completions"
qwen_token = "Bearer " + os.getenv("QWEN_TOKEN")
qwen_chat_url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
hgd_ollama_token = "Bearer sk-114514"
hgd_ollama_url = "http://10.249.188.48:11430/v1/chat/completions"

models = {
    "gemini-2.0-flash-001": {  # As of Dec. 2024
        "enabled": False,
        "url": ohmygpt_chat_url,
        "token": ohmygpt_token,
    },
    "deepseek-chat": {
        "enabled": False,
        "url": oneapi_chat_url,
        "token": oneapi_token,
    },
    "qwen2.5-coder-32b-instruct": {
        "enabled": True,
        "url": hgd_ollama_url,
        "token": hgd_ollama_token,
    },
}

system_prompt_old = """
The user will provide Java methods from an Android Binder handler. The first method is the entry point.
Analyze the code for explicit security checks including but not limited to permissions, UID validation, or signature validation. Provide a brief description of any security checks found.
At the same time, check if the code may process sensitive user data, such as location, camera, or contacts.

You may assume a function call to be a security check based on the method name, e.g., `enforcePermission` or `checkPermission`, if the method is not provided in the code snippet.

NOTE: usual input parameter checks, including null checks and `enforceInterface` calls, are not considered security checks.

Return the result as a JSON object, DO NOT include any other information in the response.

EXAMPLE JSON OUTPUT:
{
    "is_not_empty": false, // Whether the code has functionality instead of being a stub or placeholder
    "clears_calling_identity": false, // Whether the code clears the calling identity
    "contains_security_check": true, // Whether the code contains a security check
    "description": "The code contains a permission check for the CAMERA permission.",
    "permission": "CAMERA" // Optional, null if not applicable, SINGLE string if applicable
    "sensitive": true // Whether if the code may process sensitive user data
}
"""

system_prompt = """
Analyze the following Android Binder handler method for security implications. The first method provided is the entry point.

Required Analysis:
1. Security Checks (any of):
   - Permission checks (e.g., enforcePermission, checkPermission)
   - UID validation
   - Signature validation
   Note: Exclude standard input validation (null checks, enforceInterface)

2. Binder Identity Management:
   - Track if clearCallingIdentity or other methods that clears calling identity is used

3. Sensitive Data Processing:
   - Location data
   - Camera access
   - Contact information
   - Installed applications
   - Other PII

4. Implementation Status:
   - Verify if code contains actual functionality vs only logging or stubs

Output Format (JSON):
{
    "is_not_empty": boolean,  // Has actual functionality, required
    "clears_calling_identity": boolean,  // Uses clearCallingIdentity, required
    "contains_security_check": boolean,  // Has explicit security validation, required
    "description": string,  // Brief description of security checks found, required
    "permission": string | null,  // Single permission string if applicable, optional
    "sensitive": boolean  // Processes sensitive user data, required
}

Notes:
- Assume security intent for methods with security-related names (e.g., checkPermission)
- Only analyze explicit security checks
- Focus on a single primary permission if multiple exist
- Only respond with a single JSON object
"""


def escape_model_name(model_name: str):
    return model_name.replace(".", "_")


def bool_to_int(b: bool):
    return 1 if b else 0


def exec_model(code_input: str, chat_url: str, token: str, model: str, proxy: dict = None):
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": code_input},
    ]

    resp = requests.post(
        chat_url,
        json={
            "model": model,
            "messages": messages,
            "stream": False,
            "response_format": {"type": "json_object"},
        },
        headers={"Authorization": token},
        proxies=proxy,
        timeout=(5, 120),
    )
    resp.raise_for_status()

    content = resp.json()["choices"][0]["message"]["content"]

    try:
        return json.loads(content)
    except json.JSONDecodeError:
        return None


def process_item(item, model_name: str, model_info: dict):
    source_len = len(item["source"])
    source = item["source"]
    truncated = False
    if source_len > 8192:
        source = item["source"][:8192]
        truncated = True

    code_input = source

    if truncated:
        code_input += f"// Truncated from {source_len} characters"

    return exec_model(code_input, model_info["url"], model_info["token"], model_name, model_info.get("proxy"))


def worker(
    collection: pymongo.collection.Collection, txn, model_name: str, model_info: dict
):
    info_str = f"{txn['serviceName']} {txn['interfaceCode']} {txn['callee']['name']}"
    try:
        print(f"Processing {info_str} with {model_name}")
        model_resp = process_item(txn, model_name, model_info)
        print(model_resp)
        result = {
            "containsSecurityCheck": bool_to_int(model_resp["contains_security_check"]),
            "isNotEmpty": bool_to_int(model_resp["is_not_empty"]),
            "clearsCallingIdentity": bool_to_int(model_resp["clears_calling_identity"]),
            "permission": model_resp.get("permission", None),
            "description": model_resp["description"],
            "sensitive": bool_to_int(model_resp["sensitive"]),
        }
        collection.update_one(
            {"_id": txn["_id"]},
            {"$set": {f"results.{escape_model_name(model_name)}": result}},
        )
        print(f"Processed {info_str} with {model_name}")
    except Exception as e:
        print(f"Error processing {info_str}: {e}")


if __name__ == "__main__":
    mongo_client = pymongo.MongoClient(os.getenv("MONGODB_URL"))
    db = mongo_client["binder_analyzer"]
    interface_collection = db["binder_interface"]

    executor = ThreadPoolExecutor(max_workers=8)

    for txn in interface_collection.find(
        {
            "isAccessible": True,
            "isEmpty": False,
            "inBaseline": False,
            "firmwareId": {"$in": [
                bson.ObjectId("67c7c0eba4e85150ae1813fa"),
                bson.ObjectId("67bc197102e3824265dbbb74"),
                bson.ObjectId("680cba1803e5b756f518f1fa"),
            ]},
        }
    ):
        for model_name, model_info in models.items():
            if not model_info["enabled"] or escape_model_name(model_name) in txn.get(
                "results", {}
            ):
                continue
            fut = executor.submit(
                worker, interface_collection, txn, model_name, model_info
            )

    executor.shutdown(wait=True)
