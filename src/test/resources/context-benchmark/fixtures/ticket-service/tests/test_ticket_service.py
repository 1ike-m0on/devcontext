from app.services.ticket_service import TicketService


def test_create_ticket_sets_open_status():
    ticket = TicketService().create_ticket("Cannot login", "urgent")

    assert ticket.status == "open"
