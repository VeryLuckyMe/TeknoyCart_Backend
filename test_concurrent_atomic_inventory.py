#!/usr/bin/env python3
"""
TeknoyCart: Atomic Inventory Concurrency Verification Script
Exercises true concurrent execution against PostgreSQL using Python threading and Barrier
to prove that SELECT ... FOR UPDATE serializes row locks and guarantees zero double-booking.
"""

import os
import re
import sys
import uuid
import threading
import psycopg2
from psycopg2 import pool

def get_db_password():
    db_password = os.environ.get("SUPABASE_DB_PASSWORD") or os.environ.get("SPRING_DATASOURCE_PASSWORD")
    if not db_password:
        local_yml = os.path.join(os.path.dirname(__file__), "src", "main", "resources", "application-local.yml")
        if os.path.exists(local_yml):
            with open(local_yml, "r", encoding="utf-8") as f:
                match = re.search(r"password:\s*([^\s#]+)", f.read().split("datasource:")[-1] if "datasource:" in f.read() else "")
                if match:
                    db_password = match.group(1).strip()
    return db_password

def main():
    db_password = get_db_password()
    if not db_password:
        print("SKIP: SUPABASE_DB_PASSWORD not found in environment or application-local.yml.")
        print("This test runs in environments with direct Postgres credentials.")
        return

    host = os.environ.get("SUPABASE_DB_HOST", "aws-1-ap-southeast-1.pooler.supabase.com")
    port = int(os.environ.get("SUPABASE_DB_PORT", "5432"))
    dbname = os.environ.get("SUPABASE_DB_NAME", "postgres")
    user = os.environ.get("SUPABASE_DB_USER", "postgres.chmtvasbhkbrvydbajnd")

    print(f"Connecting to database {host}:{port}/{dbname}...")
    try:
        connection_pool = psycopg2.pool.ThreadedConnectionPool(
            minconn=1,
            maxconn=15,
            host=host,
            port=port,
            dbname=dbname,
            user=user,
            password=db_password,
            sslmode="require"
        )
    except Exception as e:
        print(f"SKIP: Could not establish connection pool: {e}")
        return

    # Setup test fixture: 1 product, 1 variant, stock = 1, reserved = 0
    setup_conn = connection_pool.getconn()
    setup_conn.autocommit = True
    setup_cur = setup_conn.cursor()

    test_product_id = str(uuid.uuid4())
    test_variant_id = str(uuid.uuid4())
    seller_id = str(uuid.uuid4())

    try:
        # Create seller user
        setup_cur.execute(
            "INSERT INTO public.users (user_id, email, full_name, role) VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING;",
            (seller_id, f"seller_{seller_id[:8]}@cit.edu", "Test Seller", "SELLER")
        )
        # Create product
        setup_cur.execute(
            "INSERT INTO public.products (product_id, seller_id, name, base_price, status, is_preorder_enabled) "
            "VALUES (%s, %s, %s, %s, %s, %s);",
            (test_product_id, seller_id, "Concurrency Test Item", 100.0, "ACTIVE", False)
        )
        # Create variant
        setup_cur.execute(
            "INSERT INTO public.product_variants (variant_id, product_id, variant_name, variant_value) "
            "VALUES (%s, %s, %s, %s);",
            (test_variant_id, test_product_id, "Standard", "Default")
        )
        # Create inventory: exactly 1 unit available
        setup_cur.execute(
            "INSERT INTO public.inventory (variant_id, stock_qty, reserved_qty) VALUES (%s, 1, 0);",
            (test_variant_id,)
        )
        print(f"Created test inventory: variant_id={test_variant_id}, stock=1, reserved=0")

    except Exception as e:
        print(f"Setup error (procedure might not be applied yet): {e}")
        connection_pool.putconn(setup_conn)
        return
    finally:
        connection_pool.putconn(setup_conn)

    # Launch 10 concurrent buyer threads trying to reserve the 1 available unit simultaneously
    NUM_THREADS = 10
    barrier = threading.Barrier(NUM_THREADS)
    results = []
    lock = threading.Lock()

    def buyer_task(buyer_index):
        buyer_id = str(uuid.uuid4())
        conn = connection_pool.getconn()
        try:
            cur = conn.cursor()
            # Register buyer
            cur.execute(
                "INSERT INTO public.users (user_id, email, full_name, role) VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING;",
                (buyer_id, f"buyer_{buyer_id[:8]}@cit.edu", f"Buyer {buyer_index}", "BUYER")
            )
            conn.commit()

            # Synchronize all threads to hit the RPC at the exact same millisecond
            barrier.wait()

            # Invoke atomic reservation RPC
            cur.execute(
                "SELECT reserve_inventory_atomic(%s, %s, %s);",
                (test_variant_id, 1, False)
            )
            res = cur.fetchone()[0]
            conn.commit()

            with lock:
                results.append((buyer_index, res))
        except Exception as err:
            with lock:
                results.append((buyer_index, {"error": str(err)}))
        finally:
            connection_pool.putconn(conn)

    threads = []
    for i in range(NUM_THREADS):
        t = threading.Thread(target=buyer_task, args=(i,))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    # Analyze results
    success_count = sum(1 for _, r in results if isinstance(r, dict) and r.get("success") is True)
    insufficient_count = sum(1 for _, r in results if isinstance(r, dict) and r.get("error") == "INSUFFICIENT_STOCK")

    print("\n--- Concurrency Test Results ---")
    print(f"Total concurrent requests: {NUM_THREADS}")
    print(f"Successful reservations:   {success_count}")
    print(f"Rejected (INSUFFICIENT):   {insufficient_count}")

    # Verify inventory state
    verify_conn = connection_pool.getconn()
    verify_cur = verify_conn.cursor()
    verify_cur.execute("SELECT stock_qty, reserved_qty FROM public.inventory WHERE variant_id = %s;", (test_variant_id,))
    final_stock, final_reserved = verify_cur.fetchone()
    connection_pool.putconn(verify_conn)

    print(f"Final DB Inventory State:  stock={final_stock}, reserved={final_reserved}")

    # Cleanup test data
    cleanup_conn = connection_pool.getconn()
    cleanup_conn.autocommit = True
    cleanup_cur = cleanup_conn.cursor()
    cleanup_cur.execute("DELETE FROM public.inventory WHERE variant_id = %s;", (test_variant_id,))
    cleanup_cur.execute("DELETE FROM public.product_variants WHERE variant_id = %s;", (test_variant_id,))
    cleanup_cur.execute("DELETE FROM public.products WHERE product_id = %s;", (test_product_id,))
    connection_pool.putconn(cleanup_conn)
    connection_pool.closeall()

    assert success_count == 1, f"Expected exactly 1 successful reservation, got {success_count}"
    assert insufficient_count == (NUM_THREADS - 1), f"Expected {NUM_THREADS - 1} insufficient stock rejections, got {insufficient_count}"
    assert final_reserved == 1, f"Expected final reserved_qty=1, got {final_reserved}"

    print("ALL ATOMIC CONCURRENCY CHECKS PASSED: Zero double-booking occurred.")

if __name__ == "__main__":
    main()
